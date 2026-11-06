package dev.elontusk.gtnhvoice.core;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Authenticated UDP client; heartbeats establish the NAT mapping even when not speaking. */
public final class VoiceClient implements Closeable {

    private final DatagramSocket socket;
    private final VoiceRelay.Credentials credentials;
    private final AtomicLong outgoing = new AtomicLong();
    private final Thread worker;
    private final Playback playback;
    private final Consumer<String> errors;
    private volatile long lastSeen;

    public VoiceClient(InetSocketAddress server, VoiceRelay.Credentials credentials, Playback playback,
        Consumer<String> errors) throws IOException {
        this.credentials = credentials;
        this.playback = playback;
        this.errors = errors;
        socket = new DatagramSocket();
        socket.connect(server);
        socket.setSoTimeout(250);
        worker = new Thread(this::receive, "GTNH Voice UDP client");
        worker.setDaemon(true);
        worker.start();
    }

    public boolean isConnected() {
        return !socket.isClosed() && lastSeen != 0 && System.nanoTime() - lastSeen < 6_000_000_000L;
    }

    public void sendAudio(byte[] frame) {
        if (!AudioCodec.isValid(frame) || !isConnected()) return;
        try {
            send(VoiceProtocol.AUDIO, frame);
        } catch (IOException | GeneralSecurityException ex) {
            fail(ex);
        }
    }

    private void send(int type, byte[] payload) throws GeneralSecurityException, IOException {
        byte[] packet = VoiceProtocol.seal(
            credentials.id,
            credentials.key,
            VoiceProtocol.CLIENT_DIRECTION,
            type,
            outgoing.getAndIncrement(),
            payload);
        socket.send(new DatagramPacket(packet, packet.length));
    }

    private void receive() {
        long lastPing = 0;
        long lastSequence = -1;
        byte[] buffer = new byte[VoiceProtocol.MAX_DATAGRAM + 1];
        while (!socket.isClosed()) {
            try {
                long now = System.nanoTime();
                if (now - lastPing > 2_000_000_000L) {
                    send(VoiceProtocol.PING, new byte[0]);
                    lastPing = now;
                }
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                VoiceProtocol.Message message;
                try {
                    message = VoiceProtocol.open(
                        Arrays.copyOf(buffer, packet.getLength()),
                        credentials.id,
                        credentials.key,
                        VoiceProtocol.SERVER_DIRECTION);
                } catch (GeneralSecurityException | IllegalArgumentException ignored) {
                    continue;
                }
                if (message.sequence <= lastSequence) continue;
                if (message.type == VoiceProtocol.PONG && message.payload.length == 0) {
                    lastSequence = message.sequence;
                    lastSeen = System.nanoTime();
                } else if (message.type == VoiceProtocol.PLAYBACK
                    && message.payload.length == 18 + AudioCodec.FRAME_BYTES) {
                        ByteBuffer b = ByteBuffer.wrap(message.payload);
                        UUID speaker = new UUID(b.getLong(), b.getLong());
                        double gain = (b.getShort() & 65535) / 65535.0;
                        byte[] frame = new byte[AudioCodec.FRAME_BYTES];
                        b.get(frame);
                        if (!AudioCodec.isValid(frame)) continue;
                        lastSequence = message.sequence;
                        lastSeen = System.nanoTime();
                        playback.accept(speaker, frame, gain);
                    }
            } catch (SocketTimeoutException ignored) {
                // Check heartbeat timer.
            } catch (IOException | GeneralSecurityException ex) {
                if (!socket.isClosed()) fail(ex);
            }
        }
    }

    private void fail(Exception ex) {
        if (!socket.isClosed()) errors.accept("Voice connection failed: " + ex.getMessage());
        socket.close();
    }

    @Override
    public void close() {
        socket.close();
        try {
            worker.join(1000);
        } catch (InterruptedException ex) {
            Thread.currentThread()
                .interrupt();
        }
    }

    public interface Playback {

        void accept(UUID speaker, byte[] frame, double gain);
    }
}
