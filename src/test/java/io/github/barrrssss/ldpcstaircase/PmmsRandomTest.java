package io.github.barrrssss.ldpcstaircase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Covers {@link PmmsRandom} against RFC 5170 section 5.7 / SPEC.md section 3. */
class PmmsRandomTest {

    /**
     * The RFC's own validation criterion, and the single most important assertion in
     * this repository: if this holds, an independent implementation of the same
     * recurrence walks the identical stream, and therefore builds the identical graph.
     */
    @Test
    void rfc5170ValidationCriterion() {
        PmmsRandom rng = new PmmsRandom(1);
        int value = 0;
        for (int i = 0; i < 10_000; i++) {
            value = rng.next();
        }

        assertEquals(1043618065, value, "the 10,000th value for seed 1 must match RFC 5170");
    }

    @Test
    void firstValuesFollowTheRecurrence() {
        PmmsRandom rng = new PmmsRandom(1);
        assertEquals(16807, rng.next());
        assertEquals(282475249, rng.next());
        assertEquals(1622650073, rng.next());
    }

    @Test
    void stateStaysInRangeAndNeverRepeatsEarly() {
        PmmsRandom rng = new PmmsRandom(PmmsRandom.MAX_SEED);
        for (int i = 0; i < 100_000; i++) {
            int value = rng.next();
            assertTrue(value >= 1 && value <= PmmsRandom.MAX_SEED,
                    "raw value out of range: " + value);
        }
    }

    /** The full period is 2^31 - 2; seed 1 must not recur before then. */
    @Test
    void doesNotReturnToTheSeedWithinASampleOfThePeriod() {
        PmmsRandom rng = new PmmsRandom(1);
        for (int i = 0; i < 1_000_000; i++) {
            assertTrue(rng.next() != 1, "stream returned to the seed after " + (i + 1) + " draws");
        }
    }

    @Test
    void boundedDrawsStayInRange() {
        PmmsRandom rng = new PmmsRandom(12345);
        for (int i = 0; i < 100_000; i++) {
            int maxv = 1 + (i % 1000);
            int value = rng.nextBounded(maxv);
            assertTrue(value >= 0 && value < maxv,
                    "nextBounded(" + maxv + ") returned " + value);
        }
    }

    @Test
    void boundOfOneIsAlwaysZero() {
        PmmsRandom rng = new PmmsRandom(7);
        for (int i = 0; i < 1000; i++) {
            assertEquals(0, rng.nextBounded(1));
        }
    }

    @Test
    void rejectsOutOfRangeSeeds() {
        assertThrows(LdpcStaircaseException.class, () -> new PmmsRandom(0));
        assertThrows(LdpcStaircaseException.class, () -> new PmmsRandom(-1));
        assertThrows(LdpcStaircaseException.class, () -> new PmmsRandom(Integer.MAX_VALUE));
    }

    @Test
    void rejectsNonPositiveBounds() {
        PmmsRandom rng = new PmmsRandom(1);
        assertThrows(LdpcStaircaseException.class, () -> rng.nextBounded(0));
        assertThrows(LdpcStaircaseException.class, () -> rng.nextBounded(-5));
    }

    @Test
    void sameSeedGivesTheSameStream() {
        PmmsRandom a = new PmmsRandom(999);
        PmmsRandom b = new PmmsRandom(999);
        for (int i = 0; i < 1000; i++) {
            assertEquals(a.next(), b.next());
        }
    }
}
