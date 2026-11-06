package dev.elontusk.gtnhvoice.client;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import cpw.mods.fml.relauncher.Side;
import dev.elontusk.gtnhvoice.CommonProxy;
import dev.elontusk.gtnhvoice.core.VoiceClient;
import dev.elontusk.gtnhvoice.core.VoiceProtocol;
import dev.elontusk.gtnhvoice.core.VoiceRelay;
import io.netty.buffer.Unpooled;

public final class ClientProxy extends CommonProxy {
    private final KeyBinding pushToTalk = new KeyBinding("key.gtnhvoice.ptt", Keyboard.KEY_V, "key.categories.gtnhvoice");
    private final KeyBinding mute = new KeyBinding("key.gtnhvoice.mute", Keyboard.KEY_NONE, "key.categories.gtnhvoice");
    private final ArrayBlockingQueue<Welcome> welcomes = new ArrayBlockingQueue<>(4);
    private final ArrayBlockingQueue<String> errors = new ArrayBlockingQueue<>(8);
    private volatile NetworkManager connection;
    private volatile NetworkManager registered;
    private NetworkManager current;
    private boolean helloSent;
    private boolean muted;
    private int ticks;
    private volatile VoiceClient voice;
    private VoiceAudio audio;

    @Override
    public void init(FMLPreInitializationEvent event) {
        super.init(event);
        ClientRegistry.registerKeyBinding(pushToTalk);
        ClientRegistry.registerKeyBinding(mute);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void connected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        // Forge can deliver REGISTER during negotiation, before this connection-completed event.
        if (registered != event.manager) registered = null;
        connection = event.manager;
    }

    @SubscribeEvent
    public void disconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        if (connection == event.manager) { connection = null; registered = null; }
    }

    @SubscribeEvent
    public void registration(FMLNetworkEvent.CustomPacketRegistrationEvent<?> event) {
        if (event.side != Side.CLIENT || !event.registrations.contains(CHANNEL)) return;
        if ("REGISTER".equals(event.operation)) registered = event.manager;
        else if (registered == event.manager) registered = null;
    }

    @Override
    protected void receiveWelcome(byte[] packet, NetworkManager manager) { welcomes.offer(new Welcome(manager, packet)); }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (connection != current) {
            closeVoice();
            current = connection;
            helloSent = false;
            ticks = 0;
            errors.clear();
        }
        if (current == null || mc.thePlayer == null || mc.theWorld == null) {
            if (audio != null) audio.setTransmitting(false);
            return;
        }
        ticks++;
        // REGISTER comes from the remote peer. Never send our handshake to a server without this channel.
        if (!helloSent && registered == current) {
            channel.sendToServer(new FMLProxyPacket(Unpooled.wrappedBuffer(
                ByteBuffer.allocate(5).put((byte) 0).putInt(VoiceProtocol.VERSION).array()), CHANNEL));
            helloSent = true;
        }
        Welcome welcome;
        while ((welcome = welcomes.poll()) != null) {
            if (welcome.manager != current || voice != null) continue;
            startVoice(welcome, mc);
        }
        if (mute.isPressed()) { muted = !muted; if (audio != null) audio.setMuted(muted); }
        if (audio != null) audio.setTransmitting(voice != null && voice.isConnected()
            && !muted && mc.inGameHasFocus && mc.currentScreen == null && pushToTalk.getIsKeyPressed());
        String error;
        while ((error = errors.poll()) != null) mc.thePlayer.addChatMessage(new ChatComponentText("[Voice] " + error));
    }

    private void startVoice(Welcome welcome, Minecraft mc) {
        ByteBuffer b = ByteBuffer.wrap(welcome.packet);
        if (b.get() != 1 || b.getInt() != VoiceProtocol.VERSION) return;
        int port = b.getShort() & 65535;
        UUID id = new UUID(b.getLong(), b.getLong());
        byte[] key = new byte[16]; b.get(key);
        if (port == 0) return;
        SocketAddress remote = current.getSocketAddress();
        // Integrated servers use Netty's local channel rather than an IP socket.
        InetSocketAddress endpoint = remote instanceof InetSocketAddress
            ? new InetSocketAddress(((InetSocketAddress) remote).getAddress(), port)
            : new InetSocketAddress("127.0.0.1", port);
        try {
            audio = new VoiceAudio(frame -> {
                VoiceClient active = voice;
                if (active != null) active.sendAudio(frame);
            }, errors::offer, config.volume);
            audio.setMuted(muted);
            VoiceAudio output = audio;
            voice = new VoiceClient(endpoint, new VoiceRelay.Credentials(id, key), output::accept, errors::offer);
            mc.thePlayer.addChatMessage(new ChatComponentText("[Voice] Hold "
                + GameSettings.getKeyDisplayString(pushToTalk.getKeyCode()) + " to talk. Change bindings in Controls."));
        } catch (Exception ex) {
            closeVoice();
            errors.offer("Cannot start UDP voice: " + ex.getMessage());
        }
    }

    @SubscribeEvent
    public void overlay(RenderGameOverlayEvent.Text event) {
        if (current == null || Minecraft.getMinecraft().thePlayer == null) return;
        if (muted) event.left.add("Voice: muted");
        else if (voice != null && voice.isConnected()) event.left.add(audio != null && audio.isCapturing()
            ? "Voice: talking" : "Voice: ready (" + GameSettings.getKeyDisplayString(pushToTalk.getKeyCode()) + ")");
        else if (voice != null) event.left.add("Voice: UDP unavailable (check server port/firewall)");
        else if (helloSent && ticks > 100) event.left.add("Voice: disabled or incompatible on this server");
    }

    private void closeVoice() {
        if (audio != null) { audio.close(); audio = null; }
        if (voice != null) { voice.close(); voice = null; }
    }

    private static final class Welcome {
        final NetworkManager manager;
        final byte[] packet;
        Welcome(NetworkManager manager, byte[] packet) { this.manager = manager; this.packet = packet; }
    }
}
