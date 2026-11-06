package dev.elontusk.gtnhvoice;

import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLEventChannel;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import dev.elontusk.gtnhvoice.core.VoicePosition;
import dev.elontusk.gtnhvoice.core.VoiceProtocol;
import dev.elontusk.gtnhvoice.core.VoiceRelay;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Dedicated-server safe: client, LWJGL, and audio classes are confined to the sided client proxy. */
public class CommonProxy {

    public static final String CHANNEL = "gtnhvoice";
    protected static final Logger LOG = LogManager.getLogger(CHANNEL);
    protected FMLEventChannel channel;
    protected VoiceConfig config;
    private VoiceRelay relay;
    private final ArrayBlockingQueue<EntityPlayerMP> hellos = new ArrayBlockingQueue<>(256);
    private final Set<UUID> welcomed = new HashSet<>();

    public void init(FMLPreInitializationEvent event) {
        config = new VoiceConfig(event.getSuggestedConfigurationFile());
        channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(CHANNEL);
        channel.register(this);
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    public void startServer() {
        stopServer();
        if (!config.enabled) return;
        try {
            relay = new VoiceRelay(config.bindAddress, config.port, config.range, message -> LOG.warn(message));
            LOG.info("Optional proximity voice relay listening on UDP {}:{}", config.bindAddress, relay.port());
        } catch (SocketException ex) {
            LOG.error(
                "Cannot bind voice UDP port {}. Minecraft remains available; voice is disabled.",
                config.port,
                ex);
        }
    }

    public void stopServer() {
        if (relay != null) {
            relay.close();
            relay = null;
        }
        hellos.clear();
        welcomed.clear();
    }

    @SubscribeEvent
    public void serverPacket(FMLNetworkEvent.ServerCustomPacketEvent event) {
        ByteBuf data = event.packet.payload();
        if (data.readableBytes() != 5) return;
        int start = data.readerIndex();
        if (data.getByte(start) != 0 || data.getInt(start + 1) != VoiceProtocol.VERSION) return;
        if (event.handler instanceof NetHandlerPlayServer) {
            hellos.offer(((NetHandlerPlayServer) event.handler).playerEntity);
        }
    }

    @SubscribeEvent
    public void clientPacket(FMLNetworkEvent.ClientCustomPacketEvent event) {
        ByteBuf data = event.packet.payload();
        if (data.readableBytes() != 39) return;
        byte[] bytes = new byte[39];
        data.getBytes(data.readerIndex(), bytes);
        receiveWelcome(bytes, event.manager);
    }

    protected void receiveWelcome(byte[] packet, NetworkManager manager) {}

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (relay == null) {
            hellos.clear();
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return;
        Map<UUID, VoicePosition> positions = new HashMap<>();
        Set<EntityPlayerMP> online = new HashSet<>();
        for (Object entry : server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) entry;
            online.add(player);
            if (!player.isDead) positions
                .put(player.getUniqueID(), new VoicePosition(player.dimension, player.posX, player.posY, player.posZ));
        }
        relay.updatePositions(positions);
        // Bound handshake work per tick, even for a client flooding the custom channel.
        for (int i = 0; i < 32; i++) {
            EntityPlayerMP player = hellos.poll();
            if (player == null) break;
            if (!online.contains(player) || !welcomed.add(player.getUniqueID())) continue;
            VoiceRelay.Credentials credentials = relay.register(player.getUniqueID());
            int port = config.advertisedPort == 0 ? config.port : config.advertisedPort;
            byte[] welcome = ByteBuffer.allocate(39)
                .put((byte) 1)
                .putInt(VoiceProtocol.VERSION)
                .putShort((short) port)
                .putLong(credentials.id.getMostSignificantBits())
                .putLong(credentials.id.getLeastSignificantBits())
                .put(credentials.key)
                .array();
            channel.sendTo(new FMLProxyPacket(Unpooled.wrappedBuffer(welcome), CHANNEL), player);
        }
    }

    @SubscribeEvent
    public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID player = event.player.getUniqueID();
        welcomed.remove(player);
        if (relay != null) relay.remove(player);
    }
}
