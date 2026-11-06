package dev.elontusk.gtnhvoice.client;

import java.io.Closeable;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

import dev.elontusk.gtnhvoice.core.AudioCodec;

/** Audio work stays off both Minecraft and Netty threads. Microphone opens only while PTT is held. */
public final class VoiceAudio implements Closeable {

    private static final AudioFormat FORMAT = new AudioFormat(AudioCodec.SAMPLE_RATE, 16, 1, true, false);
    private final Object captureSignal = new Object();
    private final Map<UUID, Stream> streams = new HashMap<>();
    private final Consumer<byte[]> transmit;
    private final Consumer<String> errors;
    private final double volume;
    private final Thread captureWorker;
    private final Thread playbackWorker;
    private volatile boolean running = true;
    private volatile boolean transmitting;
    private volatile boolean capturing;
    private volatile boolean muted;
    private volatile boolean playbackFailed;
    private volatile TargetDataLine microphone;
    private volatile SourceDataLine speakers;

    public VoiceAudio(Consumer<byte[]> transmit, Consumer<String> errors, double volume) {
        this.transmit = transmit;
        this.errors = errors;
        this.volume = volume;
        captureWorker = new Thread(this::capture, "GTNH Voice microphone");
        playbackWorker = new Thread(this::play, "GTNH Voice playback");
        captureWorker.setDaemon(true);
        playbackWorker.setDaemon(true);
        captureWorker.start();
        playbackWorker.start();
    }

    public boolean isCapturing() {
        return capturing;
    }

    public void setTransmitting(boolean value) {
        synchronized (captureSignal) {
            transmitting = value && !muted;
            captureSignal.notifyAll();
        }
    }

    public void setMuted(boolean value) {
        muted = value;
        if (value) {
            setTransmitting(false);
            synchronized (streams) {
                streams.clear();
            }
        }
    }

    public void accept(UUID speaker, byte[] frame, double gain) {
        if (!running || muted || playbackFailed) return;
        short[] samples = AudioCodec.decode(frame);
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) Math.max(-32768, Math.min(32767, samples[i] * gain * volume));
        }
        synchronized (streams) {
            Stream stream = streams.get(speaker);
            if (stream == null) {
                if (streams.size() >= 16) return;
                stream = new Stream();
                streams.put(speaker, stream);
            }
            // Prefer fresh speech over accumulating latency when the receiver falls behind.
            if (stream.frames.size() >= 5) stream.frames.removeFirst();
            stream.frames.addLast(samples);
            stream.updated = System.nanoTime();
            streams.notifyAll();
        }
    }

    private void capture() {
        while (running) {
            TargetDataLine line = null;
            try {
                synchronized (captureSignal) {
                    while (running && !transmitting) captureSignal.wait();
                }
                if (!running) break;
                line = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, FORMAT));
                microphone = line;
                line.open(FORMAT, AudioCodec.SAMPLES * 8);
                line.start();
                capturing = true;
                byte[] pcm = new byte[AudioCodec.SAMPLES * 2];
                while (running && transmitting && !muted) {
                    int filled = 0;
                    while (filled < pcm.length && running && transmitting) {
                        int read = line.read(pcm, filled, pcm.length - filled);
                        if (read <= 0) break;
                        filled += read;
                    }
                    if (filled != pcm.length || !running || !transmitting || muted) break;
                    short[] samples = new short[AudioCodec.SAMPLES];
                    for (int i = 0; i < samples.length; i++)
                        samples[i] = (short) ((pcm[i * 2] & 255) | (pcm[i * 2 + 1] << 8));
                    transmit.accept(AudioCodec.encode(samples));
                }
            } catch (InterruptedException ex) {
                Thread.currentThread()
                    .interrupt();
                break;
            } catch (Exception ex) {
                if (running) errors.accept("Microphone unavailable. Check the OS default input device and reconnect.");
                // Do not repeatedly open an unavailable device while the key remains held.
                break;
            } finally {
                capturing = false;
                if (line != null) line.close();
                microphone = null;
            }
        }
    }

    private void play() {
        SourceDataLine line = null;
        try {
            synchronized (streams) {
                while (running && streams.isEmpty()) streams.wait();
            }
            if (!running) return;
            line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, FORMAT));
            speakers = line;
            line.open(FORMAT, AudioCodec.SAMPLES * 4);
            line.start();
            while (running) {
                int[] mix = new int[AudioCodec.SAMPLES];
                long now = System.nanoTime();
                synchronized (streams) {
                    Iterator<Stream> iterator = streams.values()
                        .iterator();
                    while (iterator.hasNext()) {
                        Stream stream = iterator.next();
                        if (now - stream.updated > 250_000_000L) {
                            iterator.remove();
                            continue;
                        }
                        // Short startup jitter buffer. Empty streams contribute silence, never old audio.
                        if (!stream.started && stream.frames.size() < 2 && now - stream.created < 40_000_000L) continue;
                        stream.started = true;
                        short[] frame = stream.frames.pollFirst();
                        if (frame != null && !muted) for (int i = 0; i < mix.length; i++) mix[i] += frame[i];
                    }
                }
                byte[] pcm = new byte[AudioCodec.SAMPLES * 2];
                for (int i = 0; i < mix.length; i++) {
                    int sample = Math.max(-32768, Math.min(32767, mix[i]));
                    pcm[i * 2] = (byte) sample;
                    pcm[i * 2 + 1] = (byte) (sample >> 8);
                }
                line.write(pcm, 0, pcm.length);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread()
                .interrupt();
        } catch (Exception ex) {
            if (running) {
                playbackFailed = true;
                errors.accept("Speakers unavailable. Check the OS default output device and reconnect.");
            }
        } finally {
            if (line != null) line.close();
            speakers = null;
        }
    }

    @Override
    public void close() {
        running = false;
        transmitting = false;
        if (microphone != null) microphone.close();
        if (speakers != null) speakers.close();
        synchronized (captureSignal) {
            captureSignal.notifyAll();
        }
        synchronized (streams) {
            streams.clear();
            streams.notifyAll();
        }
        try {
            captureWorker.join(1000);
            playbackWorker.join(1000);
        } catch (InterruptedException ex) {
            Thread.currentThread()
                .interrupt();
        }
    }

    private static final class Stream {

        final ArrayDeque<short[]> frames = new ArrayDeque<>();
        final long created = System.nanoTime();
        long updated;
        boolean started;
    }
}
