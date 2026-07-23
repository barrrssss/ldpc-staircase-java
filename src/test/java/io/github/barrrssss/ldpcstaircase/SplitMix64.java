package io.github.barrrssss.ldpcstaircase;

/**
 * The SplitMix64 generator, used only by tests and by {@link VectorGenerator} to fill
 * symbol payloads and to drive the deterministic loss-pattern searches.
 *
 * <p>It is deliberately <em>not</em> the codec's PRNG: {@link PmmsRandom} is normative
 * and must never be used for anything but matrix construction. SplitMix64 is specified
 * in a few lines of 64-bit arithmetic, so any implementation regenerating these
 * vectors reproduces the same bytes.
 */
final class SplitMix64 {

    private long state;

    SplitMix64(long seed) {
        this.state = seed;
    }

    long nextLong() {
        state += 0x9E3779B97F4A7C15L;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** A value in {@code [0, bound)}; {@code bound} must be positive. */
    int nextInt(int bound) {
        return (int) Long.remainderUnsigned(nextLong(), bound);
    }

    /** Fills {@code length} bytes from successive big-endian 64-bit outputs. */
    byte[] nextBytes(int length) {
        byte[] out = new byte[length];
        int i = 0;
        while (i < length) {
            long value = nextLong();
            for (int shift = 56; shift >= 0 && i < length; shift -= 8) {
                out[i++] = (byte) (value >>> shift);
            }
        }

        return out;
    }
}
