package dev.elontusk.gtnhvoice.core;

/** Immutable main-thread world snapshot; the UDP thread never touches Minecraft entities. */
public final class VoicePosition {
    public final int dimension;
    public final double x, y, z;

    public VoicePosition(int dimension, double x, double y, double z) {
        this.dimension = dimension; this.x = x; this.y = y; this.z = z;
    }

    public double gainTo(VoicePosition other, double range) {
        if (other == null || dimension != other.dimension || !(range > 0)) return 0;
        double dx = x - other.x, dy = y - other.y, dz = z - other.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return Math.max(0, 1 - distance / range);
    }
}
