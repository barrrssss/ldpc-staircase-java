package io.github.barrrssss.ldpcstaircase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Pins the SPEC.md section 1 profile exclusions as executable invariants.
 *
 * <p><b>Why this test exists.</b> The profile is defined as much by what it does
 * <em>not</em> do as by what it does, and those exclusions were chosen deliberately —
 * they are what keep this codec clear of the adjacent patented techniques surveyed in
 * the profile's engineering IPR review. Absences are uniquely fragile: nothing breaks, no
 * test goes red, and no user notices if a future optimisation quietly reintroduces one.
 * A decoder that returned to peeling after its dense solve, for example, would still be
 * correct, still be fast, and still pass every other test in this repository.
 *
 * <p>So these assertions are a tripwire against our own future changes, not a
 * correctness check. If one fails, the fix is not to relax the assertion: it is to stop,
 * and treat the change as a profile change requiring new conformance vectors and a fresh
 * IPR review, exactly as SPEC.md section 1 requires.
 *
 * <p>The load-bearing one is {@link #decodeNeverIteratesBetweenItsTwoStages()}.
 */
class ProfileInvariantsTest {

    /**
     * SPEC.md section 6: peel to a fixed point, then <em>at most one</em> complete
     * residual solve, and never iterate between the two.
     *
     * <p>Swept across shapes and loss patterns that exercise every path through the
     * decoder: nothing missing, peeling-only recovery, a forced residual solve, the
     * bounded-refusal path, and genuinely unrecoverable sets. In every case peeling runs
     * at most once and the residual solve runs at most once, and a solve never happens
     * without a preceding peel.
     *
     * <p>The counters are incremented inside {@code peel} and {@code residualSolve}
     * themselves rather than at their call sites, so a call introduced anywhere — from
     * inside the other stage, or from a new enclosing loop — is caught.
     */
    @Test
    void decodeNeverIteratesBetweenItsTwoStages() {
        int[][] shapes = {
                // K, R, N1, seed, symbolLength
                {16, 8, 3, 12345, 32},
                {64, 32, 7, 42, 64},
                {256, 128, 7, 7, 112},
                {512, 256, 7, 99, 24},
                {1000, 400, 7, 2026, 358},
        };

        int checked = 0;
        for (int[] shape : shapes) {
            int k = shape[0];
            int r = shape[1];
            int n = k + r;

            for (int lossPercent : new int[] {0, 5, 15, 25, 40, 60, 90}) {
                for (long dataSeed : new long[] {1L, 2L}) {
                    Fixture fixture = new Fixture(k, r, shape[2], shape[3], shape[4], dataSeed);
                    // decode() fills the present-mask in place as it recovers, so record
                    // whether anything was missing before handing it over.
                    boolean anythingMissing = lossPercent > 0;

                    boolean[] present = lossPattern(n, lossPercent, dataSeed * 31 + lossPercent);
                    byte[][] symbols = fixture.copyFor(present);
                    DecodeResult result = fixture.codec.decode(symbols, present, DecodeOptions.defaults());
                    assertStageInvariants(result, anythingMissing, shape, lossPercent);
                    checked++;

                    // The bounded-refusal path: a caller-supplied limit that the residual
                    // system exceeds must produce a clean stop, never a fallback strategy
                    // that resumes peeling or solves in pieces.
                    boolean[] present2 = lossPattern(n, lossPercent, dataSeed * 31 + lossPercent);
                    byte[][] symbols2 = fixture.copyFor(present2);
                    DecodeResult bounded = fixture.codec.decode(symbols2, present2,
                            DecodeOptions.defaults().withMaxResidualUnknowns(1));
                    assertStageInvariants(bounded, anythingMissing, shape, lossPercent);
                    assertTrue(bounded.residualSolvePassCount() == 0 || bounded.residualUnknownCount() <= 1,
                            "a bounded decode must refuse rather than solve past its limit");
                    checked++;

                    // Residual solve disabled entirely: peeling must still run exactly
                    // once and must not be retried to compensate.
                    boolean[] present3 = lossPattern(n, lossPercent, dataSeed * 31 + lossPercent);
                    byte[][] symbols3 = fixture.copyFor(present3);
                    DecodeResult peelOnly = fixture.codec.decode(symbols3, present3,
                            DecodeOptions.defaults().withResidualSolve(false));
                    assertStageInvariants(peelOnly, anythingMissing, shape, lossPercent);
                    assertEquals(0, peelOnly.residualSolvePassCount(),
                            "residual solve was disabled and must not have run");
                    checked++;
                }
            }
        }

        assertTrue(checked >= 200, "expected a broad sweep, only checked " + checked);
    }

    private static void assertStageInvariants(DecodeResult result, boolean anythingMissing, int[] shape,
            int lossPercent) {
        String where = String.format(Locale.ROOT, "K=%d R=%d N1=%d seed=%d S=%d loss=%d%% -> %s",
                shape[0], shape[1], shape[2], shape[3], shape[4], lossPercent, result);

        assertTrue(result.peelPassCount() <= 1, "peeling must run at most once: " + where);
        assertTrue(result.residualSolvePassCount() <= 1,
                "the residual solve must run at most once: " + where);
        assertEquals(anythingMissing ? 1 : 0, result.peelPassCount(),
                "peeling must run exactly once when anything is missing, and never otherwise: " + where);

        if (result.residualSolvePassCount() == 1) {
            assertEquals(1, result.peelPassCount(),
                    "a residual solve must be preceded by exactly one peel: " + where);
            assertEquals(DecodeResult.Stage.RESIDUAL_SOLVE, result.stage(),
                    "a decode that solved must report the residual stage: " + where);
            assertFalse(result.residualSolveSkipped(),
                    "a decode that solved must not also report a skip: " + where);
        } else {
            assertTrue(result.stage() != DecodeResult.Stage.RESIDUAL_SOLVE,
                    "only a real solve may report the residual stage: " + where);
        }
    }

    /**
     * SPEC.md section 2: {@code K} and {@code R} are fixed when the codec is created and
     * every repair symbol exists before anything is transmitted. The profile excludes
     * rateless and post-factum repair generation, so there must be no way to obtain an
     * additional symbol after encoding, and re-encoding must reproduce the same set
     * rather than extend it.
     */
    @Test
    void repairSymbolsAreFixedAtCreationAndCannotBeGeneratedLater() {
        int k = 128;
        int r = 64;
        Fixture fixture = new Fixture(k, r, 7, 555, 96, 3L);

        assertEquals(k, fixture.codec.sourceSymbolCount());
        assertEquals(r, fixture.codec.repairSymbolCount());
        assertEquals(k + r, fixture.codec.encodingSymbolCount(),
                "the symbol population is exactly K + R; there is no second output layer");

        byte[][] again = new byte[k + r][];
        for (int i = 0; i < k; i++) {
            again[i] = fixture.encoded[i].clone();
        }

        fixture.codec.encode(again);
        assertEquals(k + r, again.length, "encoding must not extend the symbol population");
        for (int i = 0; i < k + r; i++) {
            org.junit.jupiter.api.Assertions.assertArrayEquals(fixture.encoded[i], again[i],
                    "re-encoding must reproduce symbol " + i + ", not generate a new one");
        }
    }

    /**
     * A named tripwire for the profile's excluded techniques. It is deliberately
     * name-based and therefore weak on its own — someone determined to add inactivation
     * could call it something else — but it is free, and it catches the realistic case:
     * an optimisation added in good faith, under its ordinary name, by someone who never
     * read SPEC.md section 1.
     *
     * <p>It also serves as documentation. The list below is the profile's exclusion set,
     * and a reader who wonders why these terms are absent is pointed straight at the
     * answer.
     */
    @Test
    void thePublicApiExposesNoneOfTheExcludedTechniques() {
        String[] excluded = {
                "inactiv",      // permanent / predetermined / on-the-fly inactivation
                "defer",        // deferred-variable scheduling
                "precode",      // Raptor-style precode layer
                "intermediate", // L >= K intermediate symbols
                "rateless",     // unbounded output symbol generation
                "triangle",     // LDPC-Triangle
                "grouping",     // G > 1, multiple symbols per transport unit
        };

        List<String> offenders = new ArrayList<>();
        for (Class<?> type : new Class<?>[] {
                LdpcStaircase.class, DecodeOptions.class, DecodeResult.class, ParityCheckMatrix.class}) {
            for (Method method : type.getMethods()) {
                if (method.getDeclaringClass() == Object.class) {
                    continue;
                }

                String name = method.getName().toLowerCase(Locale.ROOT);
                for (String term : excluded) {
                    if (name.contains(term)) {
                        offenders.add(type.getSimpleName() + "." + method.getName() + " (matched '" + term + "')");
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "SPEC.md section 1 excludes these techniques; adding one is a profile change "
                        + "requiring new vectors and a fresh IPR review, not a passing test: " + offenders);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** A present-mask with roughly {@code lossPercent} of the symbols erased. */
    private static boolean[] lossPattern(int n, int lossPercent, long seed) {
        boolean[] present = new boolean[n];
        java.util.Arrays.fill(present, true);
        if (lossPercent == 0) {
            return present;
        }

        SplitMix64 rng = new SplitMix64(seed);
        int target = (n * lossPercent) / 100;
        int erased = 0;
        while (erased < target) {
            int index = (int) Long.remainderUnsigned(rng.nextLong(), n);
            if (present[index]) {
                present[index] = false;
                erased++;
            }
        }

        return present;
    }

    private static final class Fixture {
        final LdpcStaircase codec;
        final byte[][] encoded;

        Fixture(int k, int r, int n1, int seed, int symbolLength, long dataSeed) {
            this.codec = LdpcStaircase.create(k, r, seed, n1);
            this.encoded = new byte[k + r][];

            SplitMix64 rng = new SplitMix64(dataSeed);
            for (int i = 0; i < k; i++) {
                encoded[i] = rng.nextBytes(symbolLength);
            }

            codec.encode(encoded);
        }

        /** A fresh symbol array holding only what {@code present} marks. */
        byte[][] copyFor(boolean[] present) {
            byte[][] symbols = new byte[encoded.length][];
            for (int i = 0; i < encoded.length; i++) {
                symbols[i] = present[i] ? encoded[i].clone() : null;
            }

            return symbols;
        }
    }
}
