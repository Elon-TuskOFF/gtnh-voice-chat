package dev.elontusk.gtnhvoice.core;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free tests also used by Gradle's coreTest/check tasks. Assertions always execute. */
public final class CoreTests {

    private static int checks;

    public static void main(String[] args) throws Exception {
        codec();
        cryptography();
        rules();
        relay();
        client();
        System.out.println(
            "PASS: " + checks + " checks (codec, encryption, replay protection, routing, real UDP relay/client)");
    }

    private static void codec() {
        short[] samples = new short[AudioCodec.SAMPLES];
        for (int i = 0; i < samples.length; i++) samples[i] = (short) (12000 * Math.sin(i * Math.PI * 2 * 440 / 16000));
        byte[] encoded = AudioCodec.encode(samples);
        check(encoded.length == 163, "20 ms audio fits 163 bytes");
        short[] decoded = AudioCodec.decode(encoded);
        double error = 0;
        for (int i = 0; i < samples.length; i++) error += Math.abs(samples[i] - decoded[i]);
        check(error / samples.length < 700, "Speech-band codec roundtrip quality");
        short[] silence = AudioCodec.decode(AudioCodec.encode(new short[320]));
        for (short sample : silence) check(Math.abs(sample) < 30, "Silence remains quiet");
        Arrays.fill(samples, Short.MIN_VALUE);
        check(AudioCodec.decode(AudioCodec.encode(samples))[0] == Short.MIN_VALUE, "Signed predictor preserved");
        check(!AudioCodec.isValid(new byte[164]), "Oversized frame rejected");
        encoded[2] = (byte) 255;
        check(!AudioCodec.isValid(encoded), "Invalid step index rejected");
        boolean rejected = false;
        try {
            AudioCodec.decode(encoded);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, "Invalid audio cannot enter decoder");
    }

    private static void cryptography() throws Exception {
        UUID session = UUID.randomUUID();
        byte[] key = new byte[16];
        Arrays.fill(key, (byte) 19);
        byte[] body = AudioCodec.encode(new short[320]);
        byte[] packet = VoiceProtocol
            .seal(session, key, VoiceProtocol.CLIENT_DIRECTION, VoiceProtocol.AUDIO, 123, body);
        VoiceProtocol.Message opened = VoiceProtocol.open(packet, session, key, VoiceProtocol.CLIENT_DIRECTION);
        check(
            opened.sequence == 123 && opened.type == VoiceProtocol.AUDIO && Arrays.equals(body, opened.payload),
            "Encrypted roundtrip");
        check(
            !Arrays.equals(body, Arrays.copyOfRange(packet, VoiceProtocol.HEADER_BYTES, packet.length - 16)),
            "Audio encrypted on wire");
        check(packet.length <= VoiceProtocol.MAX_DATAGRAM, "Frame fits packet bound");
        check(rejected(packet, session, key, VoiceProtocol.SERVER_DIRECTION), "Directions use distinct nonces");
        byte[] wrongKey = key.clone();
        wrongKey[0] ^= 1;
        check(rejected(packet, session, wrongKey, VoiceProtocol.CLIENT_DIRECTION), "Wrong key rejected");
        for (int i = 0; i < packet.length; i++) {
            byte[] changed = packet.clone();
            changed[i] ^= 1;
            check(rejected(changed, session, key, VoiceProtocol.CLIENT_DIRECTION), "Tampering rejected at byte " + i);
        }
        check(VoiceProtocol.sessionId(new byte[512]) == null, "Oversized datagram rejected");
        check(VoiceProtocol.sessionId(new byte[2]) == null, "Truncated header rejected");
    }

    private static boolean rejected(byte[] packet, UUID id, byte[] key, int direction) {
        try {
            VoiceProtocol.open(packet, id, key, direction);
            return false;
        } catch (GeneralSecurityException expected) {
            return true;
        }
    }

    private static void rules() {
        VoicePosition origin = new VoicePosition(0, 0, 64, 0);
        check(origin.gainTo(new VoicePosition(0, 24, 64, 0), 48) == 0.5, "Distance attenuation");
        check(origin.gainTo(new VoicePosition(0, 48, 64, 0), 48) == 0, "Range boundary excluded");
        check(origin.gainTo(new VoicePosition(1, 0, 64, 0), 48) == 0, "Dimensions isolated");
        check(origin.gainTo(null, 48) == 0, "Offline players excluded");
        check(
            origin.gainTo(new VoicePosition(0, Double.NaN, 0, 0), 48) > 0 == false,
            "Nonfinite position never audible");
        RateLimit limit = new RateLimit(50, 5, 100);
        for (int i = 0; i < 5; i++) check(limit.allow(100), "Initial burst available");
        check(!limit.allow(100), "Flood exceeds burst");
        check(limit.allow(20_000_100), "Tokens replenish");
        check(!limit.allow(20_000_100), "Only one token replenished");
    }

    private static void relay() throws Exception {
        AtomicReference<String> error = new AtomicReference<>();
        try (VoiceRelay relay = new VoiceRelay("127.0.0.1", 0, 48, error::set)) {
            UUID a = UUID.randomUUID(), b = UUID.randomUUID();
            VoiceRelay.Credentials ca = relay.register(a), cb = relay.register(b);
            Map<UUID, VoicePosition> positions = new HashMap<>();
            positions.put(a, new VoicePosition(0, 0, 64, 0));
            positions.put(b, new VoicePosition(0, 24, 64, 0));
            relay.updatePositions(positions);
            try (Peer speaker = new Peer(relay.port(), ca); Peer listener = new Peer(relay.port(), cb)) {
                speaker.ping();
                listener.ping();
                byte[] audio = AudioCodec.encode(new short[320]);
                byte[] sent = speaker.send(VoiceProtocol.AUDIO, audio);
                VoiceProtocol.Message playback = listener.receive();
                check(playback.type == VoiceProtocol.PLAYBACK, "Voice routed to opted-in listener");
                ByteBuffer payload = ByteBuffer.wrap(playback.payload);
                check(
                    new UUID(payload.getLong(), payload.getLong()).equals(a),
                    "Server-authenticated speaker identity");
                check(Math.abs((payload.getShort() & 65535) / 65535.0 - 0.5) < 0.001, "Server computes gain");
                byte[] received = new byte[AudioCodec.FRAME_BYTES];
                payload.get(received);
                check(Arrays.equals(received, audio), "Audio survives encrypted relay");
                speaker.noPacket("No self echo");
                speaker.raw(sent);
                listener.noPacket("Replayed audio rejected");
                sent[sent.length - 1] ^= 1;
                speaker.raw(sent);
                listener.noPacket("Unauthenticated audio rejected");
                byte[] malformed = audio.clone();
                malformed[2] = (byte) 255;
                speaker.send(VoiceProtocol.AUDIO, malformed);
                listener.noPacket("Authenticated malformed audio rejected");
                positions.put(b, new VoicePosition(0, 49, 64, 0));
                relay.updatePositions(positions);
                speaker.send(VoiceProtocol.AUDIO, audio);
                listener.noPacket("Out-of-range listener excluded");
                positions.put(b, new VoicePosition(1, 0, 64, 0));
                relay.updatePositions(positions);
                speaker.send(VoiceProtocol.AUDIO, audio);
                listener.noPacket("Other dimension excluded");
                positions.put(b, new VoicePosition(0, 0, 64, 0));
                positions.remove(a);
                relay.updatePositions(positions);
                speaker.send(VoiceProtocol.AUDIO, audio);
                listener.noPacket("Absent sender cannot broadcast");
                positions.put(a, new VoicePosition(0, 0, 64, 0));
                relay.updatePositions(positions);
                relay.remove(a);
                speaker.send(VoiceProtocol.AUDIO, audio);
                listener.noPacket("Logout invalidates credentials");
                relay.register(b);
                listener.send(VoiceProtocol.PING, new byte[0]);
                listener.noPacket("Reconnect invalidates old credentials");
            }
            try (Peer unknown = new Peer(relay.port(), new VoiceRelay.Credentials(UUID.randomUUID(), new byte[16]))) {
                unknown.send(VoiceProtocol.PING, new byte[0]);
                unknown.noPacket("Client without a game-issued voice session receives nothing");
            }
        }
        check(error.get() == null, "Relay remains healthy after malformed input");
    }

    private static void client() throws Exception {
        AtomicReference<String> error = new AtomicReference<>();
        CountDownLatch heard = new CountDownLatch(1);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        try (VoiceRelay relay = new VoiceRelay("127.0.0.1", 0, 48, error::set)) {
            Map<UUID, VoicePosition> positions = new HashMap<>();
            positions.put(a, new VoicePosition(0, 0, 0, 0));
            positions.put(b, new VoicePosition(0, 1, 0, 0));
            relay.updatePositions(positions);
            InetSocketAddress endpoint = new InetSocketAddress("127.0.0.1", relay.port());
            try (VoiceClient sender = new VoiceClient(endpoint, relay.register(a), (id, frame, gain) -> {}, error::set);
                VoiceClient listener = new VoiceClient(
                    endpoint,
                    relay.register(b),
                    (id, frame, gain) -> {
                        if (id.equals(a) && AudioCodec.isValid(frame) && gain > 0) heard.countDown();
                    },
                    error::set)) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while ((!sender.isConnected() || !listener.isConnected()) && System.nanoTime() < deadline)
                    Thread.sleep(10);
                check(sender.isConnected() && listener.isConnected(), "UDP clients authenticate via heartbeat");
                sender.sendAudio(AudioCodec.encode(new short[320]));
                check(heard.await(2, TimeUnit.SECONDS), "Real client-to-client encrypted voice delivery");
            }
        }
        check(error.get() == null, "Client and relay threads shut down cleanly");
    }

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }

    private static final class Peer implements AutoCloseable {

        final DatagramSocket socket;
        final VoiceRelay.Credentials credentials;
        long outgoing;

        Peer(int port, VoiceRelay.Credentials credentials) throws Exception {
            this.credentials = credentials;
            socket = new DatagramSocket();
            socket.connect(new InetSocketAddress("127.0.0.1", port));
            socket.setSoTimeout(200);
        }

        byte[] send(int type, byte[] body) throws Exception {
            byte[] packet = VoiceProtocol
                .seal(credentials.id, credentials.key, VoiceProtocol.CLIENT_DIRECTION, type, outgoing++, body);
            raw(packet);
            return packet;
        }

        void raw(byte[] bytes) throws Exception {
            socket.send(new DatagramPacket(bytes, bytes.length));
        }

        VoiceProtocol.Message receive() throws Exception {
            byte[] bytes = new byte[512];
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
            socket.receive(packet);
            return VoiceProtocol.open(
                Arrays.copyOf(bytes, packet.getLength()),
                credentials.id,
                credentials.key,
                VoiceProtocol.SERVER_DIRECTION);
        }

        void ping() throws Exception {
            send(VoiceProtocol.PING, new byte[0]);
            check(receive().type == VoiceProtocol.PONG, "NAT heartbeat acknowledged");
        }

        void noPacket(String description) throws Exception {
            try {
                receive();
                throw new AssertionError(description);
            } catch (SocketTimeoutException expected) {
                check(true, description);
            }
        }

        @Override
        public void close() {
            socket.close();
        }
    }
}
