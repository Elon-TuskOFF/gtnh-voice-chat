package dev.elontusk.gtnhvoice.core;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** One UDP socket for all clients. No Minecraft or audio-device dependencies. */
public final class VoiceRelay implements Closeable {
    private final DatagramSocket socket;
    private final Thread worker;
    private final Consumer<String> errors;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private volatile Map<UUID, VoicePosition> positions = Collections.emptyMap();
    private final double range;

    public VoiceRelay(String bindAddress, int port, double range, Consumer<String> errors) throws SocketException {
        this.range = range;
        this.errors = errors;
        socket = new DatagramSocket(new InetSocketAddress(bindAddress, port));
        worker = new Thread(this::receive, "GTNH Voice UDP relay");
        worker.setDaemon(true);
        worker.start();
    }

    public int port() { return socket.getLocalPort(); }

    /** Called on the server thread after a compatible client's explicit opt-in. Rotates old credentials. */
    public synchronized Credentials register(UUID player) {
        remove(player);
        byte[] key = new byte[16];
        random.nextBytes(key);
        Credentials credentials = new Credentials(UUID.randomUUID(), key);
        sessions.put(credentials.id, new Session(player, credentials));
        return credentials;
    }

    public synchronized void remove(UUID player) {
        for (Session session : sessions.values()) {
            if (session.player.equals(player)) sessions.remove(session.credentials.id);
        }
    }

    public void updatePositions(Map<UUID, VoicePosition> next) {
        positions = Collections.unmodifiableMap(new HashMap<>(next));
    }

    private void receive() {
        byte[] buffer = new byte[VoiceProtocol.MAX_DATAGRAM + 1];
        while (!socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                if (packet.getLength() > VoiceProtocol.MAX_DATAGRAM) continue;
                handle(Arrays.copyOf(buffer, packet.getLength()), packet.getSocketAddress());
            } catch (IOException ex) {
                if (!socket.isClosed()) {
                    errors.accept("Voice UDP receiver stopped: " + ex.getMessage());
                    socket.close();
                }
            } catch (GeneralSecurityException | IllegalArgumentException ignored) {
                // Malformed/unauthenticated datagrams are dropped, without log amplification.
            }
        }
    }

    private void handle(byte[] packet, SocketAddress address) throws GeneralSecurityException {
        UUID id = VoiceProtocol.sessionId(packet);
        Session sender = id == null ? null : sessions.get(id);
        if (sender == null) return;
        long now = System.nanoTime();
        // Limit cryptographic work as well as accepted audio traffic.
        if (!sender.limit.allow(now)) return;
        VoiceProtocol.Message message = VoiceProtocol.open(packet, id, sender.credentials.key,
            VoiceProtocol.CLIENT_DIRECTION);
        if (message.sequence <= sender.lastSequence) return;
        if (message.type != VoiceProtocol.PING && message.type != VoiceProtocol.AUDIO) return;
        if (message.type == VoiceProtocol.PING && message.payload.length != 0) return;
        if (message.type == VoiceProtocol.AUDIO && !AudioCodec.isValid(message.payload)) return;
        sender.lastSequence = message.sequence;
        sender.address = address;
        sender.lastSeen = now;
        if (message.type == VoiceProtocol.PING) {
            send(sender, VoiceProtocol.PONG, new byte[0]);
            return;
        }
        Map<UUID, VoicePosition> snapshot = positions;
        VoicePosition source = snapshot.get(sender.player);
        if (source == null) return;
        for (Session listener : sessions.values()) {
            if (listener == sender || listener.address == null || now - listener.lastSeen > 10_000_000_000L) continue;
            double gain = source.gainTo(snapshot.get(listener.player), range);
            if (!(gain > 0)) continue;
            byte[] playback = ByteBuffer.allocate(18 + AudioCodec.FRAME_BYTES)
                .putLong(sender.player.getMostSignificantBits()).putLong(sender.player.getLeastSignificantBits())
                .putShort((short) Math.round(gain * 65535)).put(message.payload).array();
            send(listener, VoiceProtocol.PLAYBACK, playback);
        }
    }

    private void send(Session session, int type, byte[] payload) throws GeneralSecurityException {
        byte[] packet = VoiceProtocol.seal(session.credentials.id, session.credentials.key,
            VoiceProtocol.SERVER_DIRECTION, type, session.outgoing++, payload);
        try {
            socket.send(new DatagramPacket(packet, packet.length, session.address));
        } catch (IOException ignored) {
            // A failed send to one peer must not shut down every other player's voice session.
        }
    }

    @Override
    public void close() {
        socket.close();
        sessions.clear();
        positions = Collections.emptyMap();
        try { worker.join(1000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    public static final class Credentials {
        public final UUID id;
        public final byte[] key;

        public Credentials(UUID id, byte[] key) {
            if (key.length != 16) throw new IllegalArgumentException("Expected AES-128 key");
            this.id = id; this.key = key.clone();
        }
    }

    private static final class Session {
        final UUID player;
        final Credentials credentials;
        final RateLimit limit = new RateLimit(60, 15, System.nanoTime());
        long lastSequence = -1;
        long outgoing;
        long lastSeen;
        SocketAddress address;

        Session(UUID player, Credentials credentials) { this.player = player; this.credentials = credentials; }
    }
}
