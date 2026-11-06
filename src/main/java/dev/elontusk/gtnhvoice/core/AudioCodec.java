package dev.elontusk.gtnhvoice.core;

/** Independent IMA ADPCM blocks: 20 ms, 16 kHz mono, no native codec dependency. */
public final class AudioCodec {
    public static final int SAMPLE_RATE = 16000;
    public static final int SAMPLES = 320;
    public static final int FRAME_BYTES = 163;
    private static final int[] INDEX = { -1, -1, -1, -1, 2, 4, 6, 8 };
    private static final int[] STEP = {
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50,
        55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279,
        307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166,
        1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026,
        4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635,
        13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    };

    private AudioCodec() {}

    public static boolean isValid(byte[] frame) {
        return frame != null && frame.length == FRAME_BYTES && (frame[2] & 255) <= 88;
    }

    public static byte[] encode(short[] samples) {
        if (samples.length != SAMPLES) throw new IllegalArgumentException("Expected 320 samples");
        byte[] frame = new byte[FRAME_BYTES];
        int predictor = samples[0];
        // Adapt each block's starting step to its onset; silence must not acquire a click every 20 ms.
        int initialDelta = 0;
        for (int i = 1; i < 8; i++) initialDelta = Math.max(initialDelta, Math.abs(samples[i] - samples[i - 1]) / 2);
        int index = 0;
        while (index < 88 && STEP[index] < initialDelta) index++;
        frame[0] = (byte) predictor;
        frame[1] = (byte) (predictor >> 8);
        frame[2] = (byte) index;
        for (int i = 1; i < SAMPLES; i++) {
            int step = STEP[index];
            int difference = samples[i] - predictor;
            int code = difference < 0 ? 8 : 0;
            difference = Math.abs(difference);
            int delta = step >> 3;
            if (difference >= step) { code |= 4; difference -= step; delta += step; }
            if (difference >= (step >> 1)) { code |= 2; difference -= step >> 1; delta += step >> 1; }
            if (difference >= (step >> 2)) { code |= 1; delta += step >> 2; }
            predictor = clamp(predictor + ((code & 8) == 0 ? delta : -delta), -32768, 32767);
            index = clamp(index + INDEX[code & 7], 0, 88);
            frame[3 + (i - 1) / 2] |= (byte) (code << (((i - 1) % 2) * 4));
        }
        return frame;
    }

    public static short[] decode(byte[] frame) {
        if (!isValid(frame)) throw new IllegalArgumentException("Invalid audio frame");
        short[] samples = new short[SAMPLES];
        int predictor = (short) ((frame[0] & 255) | (frame[1] << 8));
        int index = frame[2] & 255;
        samples[0] = (short) predictor;
        for (int i = 1; i < SAMPLES; i++) {
            int code = (frame[3 + (i - 1) / 2] >> (((i - 1) % 2) * 4)) & 15;
            int step = STEP[index];
            int delta = (step >> 3) + ((code & 4) != 0 ? step : 0)
                + ((code & 2) != 0 ? step >> 1 : 0) + ((code & 1) != 0 ? step >> 2 : 0);
            predictor = clamp(predictor + ((code & 8) == 0 ? delta : -delta), -32768, 32767);
            index = clamp(index + INDEX[code & 7], 0, 88);
            samples[i] = (short) predictor;
        }
        return samples;
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
