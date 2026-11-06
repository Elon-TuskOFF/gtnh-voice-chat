package dev.elontusk.gtnhvoice;

import java.util.Map;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.network.NetworkCheckHandler;
import cpw.mods.fml.relauncher.Side;

@Mod(modid = GtnhVoiceChat.MOD_ID, name = "GTNH Voice Chat", version = "0.1.0",
    acceptedMinecraftVersions = "[1.7.10]", acceptableRemoteVersions = "*")
public final class GtnhVoiceChat {
    public static final String MOD_ID = "gtnhvoice";

    @SidedProxy(clientSide = "dev.elontusk.gtnhvoice.client.ClientProxy",
        serverSide = "dev.elontusk.gtnhvoice.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) { proxy.init(event); }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) { proxy.startServer(); }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) { proxy.stopServer(); }

    @NetworkCheckHandler
    public boolean checkRemoteMods(Map<String, String> mods, Side remoteSide) {
        // Only voice requires our application handshake. Never gate Minecraft login on this mod.
        return true;
    }
}
