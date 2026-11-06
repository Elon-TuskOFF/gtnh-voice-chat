package dev.elontusk.gtnhvoice;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public final class VoiceConfig {
    public final boolean enabled;
    public final String bindAddress;
    public final int port;
    public final int advertisedPort;
    public final double range;
    public final double volume;

    VoiceConfig(File file) {
        Configuration config = new Configuration(file);
        config.load();
        enabled = config.getBoolean("enabled", "server", true, "Enable the optional voice relay.");
        bindAddress = config.getString("bindAddress", "server", "0.0.0.0", "UDP interface; restart server after changes.");
        port = config.getInt("port", "server", 24454, 1, 65535, "The single UDP port used for all voice traffic.");
        advertisedPort = config.getInt("advertisedPort", "server", 0, 0, 65535,
            "External UDP port when NAT maps a different port; 0 uses port. Host is the Minecraft server IP.");
        range = config.getFloat("range", "server", 48.0F, 1.0F, 256.0F, "Proximity voice radius in blocks.");
        volume = config.getFloat("volume", "client", 1.0F, 0.0F, 2.0F, "Local playback volume; restart game after changes.");
        if (config.hasChanged()) config.save();
    }
}
