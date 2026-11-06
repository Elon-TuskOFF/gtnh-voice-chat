package dev.elontusk.gtnhvoice.core;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** UDP wire format. Sessions/keys are delivered only over the authenticated Minecraft connection. */
public final class VoiceProtocol {
    public static final int VERSION = 1;
    public static final int MAX_DATAGRAM = 256;
    public static final int HEADER_BYTES = 30;
    public static final int CLIENT_DIRECTION = 0x434c4e54;
    public static final int SERVER_DIRECTION = 0x53525652;
    public static final int PING = 0;
    public static final int AUDIO = 1;
    public static final int PONG = 2;
    public static final int PLAYBACK = 3;
    private static final int MAGIC = 0x47564f49;

    private VoiceProtocol() {}

    public static UUID sessionId(byte[] packet) {
        if (packet.length < HEADER_BYTES + 16 || packet.length > MAX_DATAGRAM) return null;
        ByteBuffer b = ByteBuffer.wrap(packet);
        if (b.getInt() != MAGIC || b.get() != VERSION) return null;
        b.get();
        return new UUID(b.getLong(), b.getLong());
    }

    public static byte[] seal(UUID session, byte[] key, int direction, int type, long sequence, byte[] payload)
        throws GeneralSecurityException {
        if (sequence < 0 || payload.length > MAX_DATAGRAM - HEADER_BYTES - 16) {
            throw new IllegalArgumentException("Invalid datagram size or sequence");
        }
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        header.putInt(MAGIC).put((byte) VERSION).put((byte) type)
            .putLong(session.getMostSignificantBits()).putLong(session.getLeastSignificantBits()).putLong(sequence);
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, direction, sequence);
        cipher.updateAAD(header.array());
        byte[] body = cipher.doFinal(payload);
        return ByteBuffer.allocate(HEADER_BYTES + body.length).put(header.array()).put(body).array();
    }

    public static Message open(byte[] packet, UUID session, byte[] key, int direction)
        throws GeneralSecurityException {
        if (!session.equals(sessionId(packet))) throw new GeneralSecurityException("Invalid session/header");
        ByteBuffer b = ByteBuffer.wrap(packet);
        int type = packet[5] & 255;
        long sequence = b.getLong(22);
        if (sequence < 0) throw new GeneralSecurityException("Invalid sequence");
        Cipher cipher = cipher(Cipher.DECRYPT_MODE, key, direction, sequence);
        cipher.updateAAD(packet, 0, HEADER_BYTES);
        return new Message(type, sequence, cipher.doFinal(packet, HEADER_BYTES, packet.length - HEADER_BYTES));
    }

    private static Cipher cipher(int mode, byte[] key, int direction, long sequence)
        throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] nonce = ByteBuffer.allocate(12).putInt(direction).putLong(sequence).array();
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return cipher;
    }

    public static final class Message {
        public final int type;
        public final long sequence;
        public final byte[] payload;

        Message(int type, long sequence, byte[] payload) {
            this.type = type; this.sequence = sequence; this.payload = payload;
        }
    }
}
