package io.github.barrrssss.ldpcstaircase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Encode/decode behaviour of {@link LdpcStaircase} (SPEC.md sections 5 and 6). */
class LdpcStaircaseTest {

    // ------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "K={0} R={1} N1={2} S={3}")
    @CsvSource({
            "16, 8, 3, 32",
            "64, 32, 7, 17",
            "100, 50, 7, 1",
            "250, 25, 7, 818",
            "1, 1, 1, 8",
    })
    void encodedSymbolsSatisfyEveryEquation(int k, int r, int n1, int symbolLength) {
        Fixture fixture = new Fixture(k, r, n1, 12345, symbolLength, 1);
        assertTrue(fixture.codec.verify(fixture.encoded), "every parity equation must XOR to zero");
    }

    @Test
    void encodingIsDeterministic() {
        Fixture a = new Fixture(64, 32, 7, 42, 25, 7);
        Fixture b = new Fixture(64, 32, 7, 42, 25, 7);
        for (int i = 0; i < a.codec.encodingSymbolCount(); i++) {
            assertArrayEquals(a.encoded[i], b.encoded[i], "symbol " + i + " differs between runs");
        }
    }

    /**
     * SPEC.md section 5: repair symbol {@code p} is its equation's source symbols XORed
     * with repair symbol {@code p-1}. Recomputing that by hand must reproduce the
     * encoder's output exactly.
     */
    @Test
    void repairSymbolsFollowTheRunningXorDefinition() {
        int k = 32;
        int r = 16;
        Fixture fixture = new Fixture(k, r, 5, 77, 24, 3);
        ParityCheckMatrix h = fixture.codec.parityCheckMatrix();

        byte[] previous = null;
        for (int p = 0; p < r; p++) {
            byte[] expected = new byte[24];
            for (int c : h.rowColumns(p)) {
                if (c < k) {
                    Symbols.xorInto(expected, fixture.encoded[c]);
                }
            }

            if (previous != null) {
                Symbols.xorInto(expected, previous);
            }

            assertArrayEquals(expected, fixture.encoded[k + p], "repair symbol " + p);
            previous = expected;
        }
    }

    @Test
    void verifyRejectsACorruptedSymbol() {
        Fixture fixture = new Fixture(32, 16, 5, 5, 16, 11);
        assertTrue(fixture.codec.verify(fixture.encoded));
        fixture.encoded[3][0] ^= 0x01;
        assertFalse(fixture.codec.verify(fixture.encoded), "a flipped bit must break some equation");
    }

    @Test
    void noRepairCodeEncodesNothing() {
        Fixture fixture = new Fixture(8, 0, 7, 1, 16, 2);
        assertEquals(8, fixture.codec.encodingSymbolCount());
        assertTrue(fixture.codec.verify(fixture.encoded), "an empty equation set is trivially satisfied");
    }

    // ------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------

    @Test
    void nothingMissingIsANoOp() {
        Fixture fixture = new Fixture(32, 16, 5, 3, 16, 4);
        Session session = fixture.session(allPresent(fixture));
        DecodeResult result = session.decode();

        assertTrue(result.isComplete());
        assertEquals(DecodeResult.Stage.NONE, result.stage());
        assertEquals(0, result.recoveredSymbolCount());
        session.assertMatchesOriginal();
    }

    /** With every source symbol in hand the staircase rebuilds the repair symbols. */
    @Test
    void missingRepairSymbolsAreRebuilt() {
        Fixture fixture = new Fixture(32, 16, 5, 3, 16, 4);
        boolean[] present = allPresent(fixture);
        for (int p = 0; p < 16; p++) {
            present[32 + p] = false;
        }

        Session session = fixture.session(present);
        DecodeResult result = session.decode();

        assertTrue(result.isComplete());
        assertEquals(DecodeResult.Stage.PEELING, result.stage());
        assertEquals(16, result.recoveredSymbolCount());
        assertEquals(0, result.recoveredSourceCount());
        session.assertMatchesOriginal();
    }

    @ParameterizedTest(name = "lose {1} of K={0} source symbols")
    @CsvSource({
            "64, 1",
            "64, 5",
            "64, 16",
            "64, 30",
    })
    void randomSourceLossIsRecovered(int k, int lost) {
        Fixture fixture = new Fixture(k, k / 2, 7, 4242, 32, 9);
        boolean[] present = allPresent(fixture);
        SplitMix64 rng = new SplitMix64(lost);
        int removed = 0;
        while (removed < lost) {
            int index = rng.nextInt(k);
            if (present[index]) {
                present[index] = false;
                removed++;
            }
        }

        Session session = fixture.session(present);
        DecodeResult result = session.decode();

        assertTrue(result.isComplete(), "decode failed: " + result);
        assertEquals(lost, result.recoveredSourceCount());
        session.assertMatchesOriginal();
    }

    /**
     * A whole lost sheet is a contiguous run of symbol indices — the correlated damage
     * pattern that matters for printed backups, and the one an interleaved page layout
     * exists to break up.
     */
    @Test
    void contiguousBandLossIsRecovered() {
        int k = 200;
        int r = 100;
        Fixture fixture = new Fixture(k, r, 7, 8181, 40, 21);
        boolean[] present = allPresent(fixture);
        for (int i = 60; i < 60 + 80; i++) {
            present[i] = false;
        }

        Session session = fixture.session(present);
        DecodeResult result = session.decode();

        assertTrue(result.isComplete(), "decode failed: " + result);
        session.assertMatchesOriginal();
    }

    /** Mixed source and repair loss, which is what a lost interleaved page produces. */
    @Test
    void mixedSourceAndRepairLossIsRecovered() {
        int k = 200;
        int r = 100;
        Fixture fixture = new Fixture(k, r, 7, 616, 40, 22);
        boolean[] present = allPresent(fixture);
        for (int i = 0; i < k + r; i += 6) {
            present[i] = false;
        }

        Session session = fixture.session(present);
        DecodeResult result = session.decode();

        assertTrue(result.isComplete(), "decode failed: " + result);
        session.assertMatchesOriginal();
    }

    /**
     * The property that matters most: whenever a decode reports completion, every
     * recovered source symbol must equal the original byte-for-byte. Swept over many
     * deterministic loss patterns and both decoder stages.
     */
    @Test
    void completedDecodesAlwaysReproduceTheSource() {
        int k = 120;
        int r = 60;
        Fixture fixture = new Fixture(k, r, 7, 31337, 24, 55);
        SplitMix64 rng = new SplitMix64(0xC0FFEEL);

        int complete = 0;
        for (int trial = 0; trial < 300; trial++) {
            boolean[] present = allPresent(fixture);
            int lost = 1 + rng.nextInt(r);
            for (int i = 0; i < lost; i++) {
                present[rng.nextInt(k + r)] = false;
            }

            Session session = fixture.session(present);
            DecodeResult result = session.decode();
            if (result.isComplete()) {
                complete++;
                session.assertMatchesOriginal();
            } else {
                // A failed decode must still report exactly what is missing.
                for (int index : result.missingSourceIndices()) {
                    assertTrue(index >= 0 && index < k, "missing index out of range: " + index);
                }

                assertEquals(result.missingSourceIndices().length,
                        Arrays.stream(result.missingSourceIndices()).distinct().count());
            }
        }

        assertTrue(complete > 200, "expected most sampled losses to be recoverable; got " + complete);
    }

    /**
     * Both stages must agree byte-for-byte. Where peeling alone succeeds, running with
     * the residual solve enabled must produce identical symbols.
     */
    @Test
    void peelingOnlyAndResidualAgreeWherePeelingSucceeds() {
        Fixture fixture = new Fixture(120, 60, 7, 2468, 24, 66);
        SplitMix64 rng = new SplitMix64(99);

        for (int trial = 0; trial < 200; trial++) {
            boolean[] present = allPresent(fixture);
            for (int i = 0; i < 30; i++) {
                present[rng.nextInt(180)] = false;
            }

            Session peelingOnly = fixture.session(present.clone());
            DecodeResult peeled = peelingOnly.decode(DecodeOptions.defaults().withResidualSolve(false));
            if (!peeled.isComplete()) {
                continue;
            }

            Session full = fixture.session(present.clone());
            DecodeResult solved = full.decode();
            assertTrue(solved.isComplete());
            assertEquals(DecodeResult.Stage.PEELING, solved.stage(),
                    "peeling already succeeded, so the residual solve must not run");
            for (int i = 0; i < 180; i++) {
                assertArrayEquals(peelingOnly.symbols[i], full.symbols[i], "symbol " + i);
            }
        }
    }

    /**
     * The residual solve is not optional: sparse peeling stalls on patterns the
     * received set still determines. This sweep asserts the solve recovers cases
     * peeling alone strands, and that both agree wherever both succeed.
     */
    @Test
    void residualSolveRecoversWherePeelingStalls() {
        Fixture fixture = new Fixture(120, 60, 7, 2468, 24, 66);
        SplitMix64 rng = new SplitMix64(0xBEEF);

        int stalled = 0;
        int rescued = 0;
        for (int trial = 0; trial < 400; trial++) {
            boolean[] present = allPresent(fixture);
            int lost = 45 + rng.nextInt(15);
            for (int i = 0; i < lost; i++) {
                present[rng.nextInt(180)] = false;
            }

            Session peelingOnly = fixture.session(present.clone());
            if (peelingOnly.decode(DecodeOptions.defaults().withResidualSolve(false)).isComplete()) {
                continue;
            }

            stalled++;
            Session full = fixture.session(present.clone());
            DecodeResult result = full.decode();
            assertEquals(DecodeResult.Stage.RESIDUAL_SOLVE, result.stage());
            assertTrue(result.residualUnknownCount() > 0);
            if (result.isComplete()) {
                rescued++;
                full.assertMatchesOriginal();
            }
        }

        assertTrue(stalled > 0, "the sweep never stalled peeling; it proves nothing");
        assertTrue(rescued > 0, "the residual solve never rescued a stalled decode");
    }

    /**
     * LDPC-Staircase is not MDS. This deterministic sweep holds exactly {@code K}
     * symbols — always enough for Reed-Solomon — and shows that some patterns still
     * fail, which is why callers must read {@link DecodeResult#isComplete()} rather
     * than count received symbols.
     */
    @Test
    void exactlyKReceivedSymbolsIsNotAGuarantee() {
        int k = 120;
        int r = 60;
        Fixture fixture = new Fixture(k, r, 7, 31337, 24, 77);
        SplitMix64 rng = new SplitMix64(0xD15EA5EL);

        int failures = 0;
        int successes = 0;
        for (int trial = 0; trial < 200; trial++) {
            boolean[] present = allPresent(fixture);
            int removed = 0;
            while (removed < r) {
                int index = rng.nextInt(k + r);
                if (present[index]) {
                    present[index] = false;
                    removed++;
                }
            }

            DecodeResult result = fixture.session(present).decode();
            if (result.isComplete()) {
                successes++;
            } else {
                failures++;
            }
        }

        assertTrue(failures > 0,
                "expected the maximum-loss sweep to expose the non-MDS margin; all " + successes
                        + " patterns succeeded");
    }

    @Test
    void unrecoverableSessionReportsWhatIsMissing() {
        int k = 64;
        int r = 32;
        Fixture fixture = new Fixture(k, r, 7, 12, 16, 88);

        // Strip far more than the code can carry: fewer received symbols than K makes
        // recovery impossible for any code.
        boolean[] present = allPresent(fixture);
        for (int i = 0; i < k + r; i++) {
            present[i] = i % 4 == 0;
        }

        Session session = fixture.session(present);
        DecodeResult result = session.decode();

        assertFalse(result.isComplete());
        assertTrue(result.missingSourceIndices().length > 0);
        for (int index : result.missingSourceIndices()) {
            assertFalse(session.present[index], "index " + index + " is reported missing but is present");
        }
    }

    @Test
    void residualSolveCanBeBoundedForMemory() {
        int k = 120;
        int r = 60;
        Fixture fixture = new Fixture(k, r, 7, 31337, 24, 99);
        boolean[] present = allPresent(fixture);
        for (int i = 0; i < 58; i++) {
            present[i * 3 % (k + r)] = false;
        }

        Session unbounded = fixture.session(present.clone());
        DecodeResult reference = unbounded.decode();
        if (reference.stage() != DecodeResult.Stage.RESIDUAL_SOLVE) {
            return; // This pattern peels cleanly; nothing to bound.
        }

        Session bounded = fixture.session(present.clone());
        DecodeResult result = bounded.decode(
                DecodeOptions.defaults().withMaxResidualUnknowns(reference.residualUnknownCount() - 1));

        assertFalse(result.isComplete());
        assertTrue(result.residualSolveSkipped());
        assertEquals(DecodeResult.Stage.PEELING, result.stage());
        assertEquals(reference.residualUnknownCount(), result.residualUnknownCount());
    }

    @Test
    void noRepairCodeDecodesOnlyWhenNothingIsMissing() {
        Fixture fixture = new Fixture(8, 0, 7, 1, 16, 100);

        boolean[] present = allPresent(fixture);
        assertTrue(fixture.session(present).decode().isComplete());

        present[3] = false;
        DecodeResult result = fixture.session(present).decode();
        assertFalse(result.isComplete());
        assertArrayEquals(new int[] {3}, result.missingSourceIndices());
    }

    // ------------------------------------------------------------------
    // Progress reporting
    // ------------------------------------------------------------------

    @Test
    void progressIsMonotoneAndEndsExactlyAtTheTotal() {
        Fixture fixture = new Fixture(200, 100, 7, 555, 32, 111);
        boolean[] present = allPresent(fixture);
        for (int i = 0; i < 90; i++) {
            present[i * 3 % 300] = false;
        }

        List<long[]> ticks = new ArrayList<>();
        DecodeResult result = fixture.session(present).decode(
                DecodeOptions.defaults().withProgressListener(
                        (done, total) -> ticks.add(new long[] {done, total})));

        assertTrue(result.isComplete(), "decode failed: " + result);
        assertFalse(ticks.isEmpty(), "expected at least one progress tick");

        long total = ticks.get(0)[1];
        assertTrue(total > 0);
        long previous = -1;
        for (long[] tick : ticks) {
            assertEquals(total, tick[1], "totalUnits must be constant within one decode");
            assertTrue(tick[0] >= previous, "progress went backwards");
            assertTrue(tick[0] <= total, "progress exceeded the total");
            previous = tick[0];
        }

        long[] last = ticks.get(ticks.size() - 1);
        assertEquals(total, last[0], "the final tick must report completion");
    }

    @Test
    void progressListenerCanCancelADecode() {
        Fixture fixture = new Fixture(200, 100, 7, 555, 32, 112);
        boolean[] present = allPresent(fixture);
        for (int i = 0; i < 90; i++) {
            present[i * 3 % 300] = false;
        }

        assertThrows(IllegalStateException.class, () -> fixture.session(present).decode(
                DecodeOptions.defaults().withProgressListener((done, total) -> {
                    throw new IllegalStateException("cancelled");
                })));
    }

    // ------------------------------------------------------------------
    // split / join
    // ------------------------------------------------------------------

    @Test
    void splitAndJoinRoundTrip() {
        byte[] data = new SplitMix64(4).nextBytes(1000);
        byte[][] symbols = LdpcStaircase.split(data, 40, 32);

        assertEquals(40, symbols.length);
        for (byte[] symbol : symbols) {
            assertEquals(32, symbol.length);
        }

        assertArrayEquals(data, LdpcStaircase.join(symbols, data.length));
    }

    @Test
    void splitZeroPadsTheTail() {
        byte[] data = new byte[] {1, 2, 3};
        byte[][] symbols = LdpcStaircase.split(data, 2, 4);
        assertArrayEquals(new byte[] {1, 2, 3, 0}, symbols[0]);
        assertArrayEquals(new byte[] {0, 0, 0, 0}, symbols[1]);
    }

    /** Unlike erasure16's GF(2^16) words, GF(2) symbols have no even-length rule. */
    @Test
    void oddSymbolLengthsAreLegal() {
        byte[][] symbols = LdpcStaircase.split(new SplitMix64(5).nextBytes(21), 3, 7);
        assertEquals(7, symbols[0].length);
    }

    @Test
    void splitAndJoinRejectBadArguments() {
        assertThrows(LdpcStaircaseException.class, () -> LdpcStaircase.split(null, 4, 8));
        assertThrows(LdpcStaircaseException.class, () -> LdpcStaircase.split(new byte[8], 0, 8));
        assertThrows(LdpcStaircaseException.class, () -> LdpcStaircase.split(new byte[8], 4, 0));
        assertThrows(LdpcStaircaseException.class, () -> LdpcStaircase.split(new byte[100], 4, 8));
        assertThrows(LdpcStaircaseException.class, () -> LdpcStaircase.join(null, 0));
        assertThrows(LdpcStaircaseException.class,
                () -> LdpcStaircase.join(new byte[][] {new byte[4]}, -1));
        assertThrows(LdpcStaircaseException.class,
                () -> LdpcStaircase.join(new byte[][] {new byte[4]}, 5));
    }

    // ------------------------------------------------------------------
    // Argument validation
    // ------------------------------------------------------------------

    @Test
    void constructionRejectsBadParameters() {
        assertThrows(LdpcStaircaseException.class, () -> LdpcStaircase.create(0, 8, 1));
        assertThrows(LdpcStaircaseException.class, () -> LdpcStaircase.create(16, -1, 1));
        assertThrows(LdpcStaircaseException.class, () -> LdpcStaircase.create(16, 8, 0));
        assertThrows(LdpcStaircaseException.class, () -> LdpcStaircase.create(16, 8, 1, 0));
        assertThrows(LdpcStaircaseException.class, () -> LdpcStaircase.create(16, 8, 1, 9));
        assertThrows(LdpcStaircaseException.class, () -> LdpcStaircase.of(null));
    }

    @Test
    void encodeRejectsMalformedSymbolArrays() {
        LdpcStaircase codec = LdpcStaircase.create(4, 2, 1, 2);

        assertThrows(LdpcStaircaseException.class, () -> codec.encode(null));
        assertThrows(LdpcStaircaseException.class, () -> codec.encode(new byte[3][8]));
        assertThrows(LdpcStaircaseException.class, () -> codec.encode(new byte[6][0]));

        byte[][] withNull = new byte[6][8];
        withNull[1] = null;
        assertThrows(LdpcStaircaseException.class, () -> codec.encode(withNull));

        byte[][] ragged = new byte[6][8];
        ragged[2] = new byte[9];
        assertThrows(LdpcStaircaseException.class, () -> codec.encode(ragged));
    }

    @Test
    void decodeRejectsMalformedSymbolArrays() {
        LdpcStaircase codec = LdpcStaircase.create(4, 2, 1, 2);
        byte[][] symbols = new byte[6][8];

        assertThrows(LdpcStaircaseException.class, () -> codec.decode(symbols, null));
        assertThrows(LdpcStaircaseException.class, () -> codec.decode(symbols, new boolean[5]));
        assertThrows(LdpcStaircaseException.class, () -> codec.decode(symbols, new boolean[6]),
                "no symbol present means no symbol length to work from");
        assertThrows(LdpcStaircaseException.class,
                () -> codec.decode(symbols, allTrue(6), null));

        byte[][] missingBacking = new byte[6][8];
        missingBacking[0] = null;
        assertThrows(LdpcStaircaseException.class, () -> codec.decode(missingBacking, allTrue(6)));

        byte[][] ragged = new byte[6][8];
        ragged[1] = new byte[9];
        assertThrows(LdpcStaircaseException.class, () -> codec.decode(ragged, allTrue(6)));
    }

    @Test
    void optionsRejectNegativeBounds() {
        assertThrows(LdpcStaircaseException.class,
                () -> DecodeOptions.defaults().withMaxResidualUnknowns(-1));
    }

    @Test
    void aPrebuiltMatrixCanBeReused() {
        ParityCheckMatrix h = ParityCheckMatrix.build(32, 16, 5, 4242);
        LdpcStaircase codec = LdpcStaircase.of(h);
        assertEquals(32, codec.sourceSymbolCount());
        assertEquals(16, codec.repairSymbolCount());
        assertEquals(5, codec.leftDegree());
        assertEquals(4242, codec.seed());
        assertNotNull(codec.toString());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static boolean[] allPresent(Fixture fixture) {
        return allTrue(fixture.codec.encodingSymbolCount());
    }

    private static boolean[] allTrue(int length) {
        boolean[] present = new boolean[length];
        Arrays.fill(present, true);
        return present;
    }

    /** An encoded symbol set plus the code that produced it. */
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

        /** A decode session holding only the symbols {@code present} marks. */
        Session session(boolean[] present) {
            return new Session(this, present);
        }
    }

    /** One decode attempt over a copy of the fixture's symbols. */
    private static final class Session {
        final Fixture fixture;
        final byte[][] symbols;
        final boolean[] present;

        Session(Fixture fixture, boolean[] present) {
            this.fixture = fixture;
            this.present = present;
            this.symbols = new byte[fixture.encoded.length][];
            for (int i = 0; i < symbols.length; i++) {
                if (present[i]) {
                    symbols[i] = fixture.encoded[i].clone();
                }
            }
        }

        DecodeResult decode() {
            return fixture.codec.decode(symbols, present);
        }

        DecodeResult decode(DecodeOptions options) {
            return fixture.codec.decode(symbols, present, options);
        }

        void assertMatchesOriginal() {
            for (int i = 0; i < fixture.codec.sourceSymbolCount(); i++) {
                assertTrue(present[i], "source symbol " + i + " was not recovered");
                assertArrayEquals(fixture.encoded[i], symbols[i], "source symbol " + i);
            }

            for (int i = fixture.codec.sourceSymbolCount(); i < symbols.length; i++) {
                if (present[i]) {
                    assertArrayEquals(fixture.encoded[i], symbols[i], "repair symbol " + i);
                }
            }
        }
    }
}
