package io.github.barrrssss.ldpcstaircase;

/**
 * The Park-Miller "minimal standard" pseudo-random number generator required by
 * RFC 5170 section 5.7 and pinned by SPEC.md section 3. It is the only source of
 * randomness in the codec: the parity check matrix is a pure function of
 * {@code (K, R, N1, seed)} because every draw comes from one stream started at
 * {@code seed}.
 *
 * <p>The recurrence is {@code state <- 16807 * state mod (2^31 - 1)}, evaluated with
 * exact 64-bit intermediates. RFC 5170's validation criterion — seed {@code 1}, the
 * 10,000th raw value is {@code 1043618065} — is asserted by {@code PmmsRandomTest}.
 *
 * <p>This class is not thread-safe; it is a mutable stream owned by one matrix build.
 */
public final class PmmsRandom {

    /** The multiplier {@code A = 7^5}. */
    public static final int MULTIPLIER = 16807;

    /** The modulus {@code M = 2^31 - 1} (a Mersenne prime). */
    public static final int MODULUS = 2147483647;

    /** Smallest legal seed. */
    public static final int MIN_SEED = 1;

    /** Largest legal seed, {@code 2^31 - 2}. */
    public static final int MAX_SEED = 2147483646;

    private int state;

    /**
     * Creates a generator seeded with {@code seed}, which must be in
     * {@code [1, 2147483646]}. Seed {@code 0} is excluded because it is the fixed
     * point of the recurrence.
     *
     * @throws LdpcStaircaseException if {@code seed} is out of range.
     */
    public PmmsRandom(int seed) {
        if (seed < MIN_SEED || seed > MAX_SEED) {
            throw new LdpcStaircaseException(
                    "seed must be in [" + MIN_SEED + ", " + MAX_SEED + "]; got " + seed + ".");
        }

        this.state = seed;
    }

    /**
     * Advances the stream and returns the new raw state, in {@code [1, 2147483646]}.
     */
    public int next() {
        // 16807 * state needs 47 bits, so the product is formed in long arithmetic
        // and reduced before it is narrowed back to int.
        state = (int) ((MULTIPLIER * (long) state) % MODULUS);
        return state;
    }

    /**
     * Advances the stream and scales the new raw state into {@code [0, maxv)}, using
     * the IEEE 754 binary64 formula RFC 5170 specifies (SPEC.md section 3):
     * {@code floor(maxv * next() / 2147483647.0)}.
     *
     * <p>The multiply-then-divide order is normative. {@code maxv * state} can reach
     * 2^62 and is rounded to a 53-bit mantissa, so this is <em>not</em> interchangeable
     * with exact 64-bit integer arithmetic; both operations are correctly rounded on
     * every conforming runtime, which is what makes the result identical on the JVM
     * and .NET.
     *
     * @throws LdpcStaircaseException if {@code maxv < 1}.
     */
    public int nextBounded(int maxv) {
        if (maxv < 1) {
            throw new LdpcStaircaseException("nextBounded: maxv must be >= 1; got " + maxv + ".");
        }

        return (int) ((double) maxv * (double) next() / 2147483647.0);
    }

    /** The current raw state, without advancing the stream. */
    public int state() {
        return state;
    }
}
