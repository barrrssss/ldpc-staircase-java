package io.github.barrrssss.ldpcstaircase;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StructuralRecoveryTest {

    @Test
    void rankOracleMatchesPayloadDecoderAcrossRandomErasures() {
        int[][] configurations = {
                // K, R, N1, seed
                {32, 8, 7, 1},
                {64, 32, 7, 42},
                {120, 60, 7, 31337},
                {250, 250, 7, 123456789},
        };

        for (int[] configuration : configurations) {
            int k = configuration[0];
            int r = configuration[1];
            LdpcStaircase codec = LdpcStaircase.create(k, r, configuration[3], configuration[2]);
            byte[][] encoded = encoded(codec, k, r, configuration[3]);
            SplitMix64 random = new SplitMix64(configuration[3] ^ 0x535452554354L);

            for (int trial = 0; trial < 200; trial++) {
                int[] order = shuffledSequence(k + r, random);
                int erasedCount = random.nextInt(r + 1);
                boolean[] erased = new boolean[k + r];
                for (int i = 0; i < erasedCount; i++) {
                    erased[order[i]] = true;
                }

                boolean structural = StructuralRecovery.isRecoverable(codec.parityCheckMatrix(), erased);
                boolean decoded = decode(codec, encoded, erased);
                assertEquals(decoded, structural,
                        "K=" + k + " R=" + r + " trial=" + trial + " erased=" + erasedCount);
            }
        }
    }

    @Test
    void onePassMarginMatchesDecoderBinarySearch() {
        int k = 120;
        int r = 120;
        LdpcStaircase codec = LdpcStaircase.create(k, r, 123456789, 7);
        byte[][] encoded = encoded(codec, k, r, 123456789);
        SplitMix64 random = new SplitMix64(0x4d415247494eL);

        for (int trial = 0; trial < 100; trial++) {
            int[] order = shuffledSequence(k + r, random);
            int expected = decoderMargin(codec, encoded, order, k, r);
            assertEquals(expected, StructuralRecovery.margin(codec.parityCheckMatrix(), order),
                    "trial=" + trial);
        }
    }

    @Test
    void residualCoreAndBoundedMarginMatchPayloadDecoder() {
        int k = 120;
        int r = 120;
        int residualLimit = 40;
        LdpcStaircase codec = LdpcStaircase.create(k, r, 8675309, 7);
        byte[][] encoded = encoded(codec, k, r, 8675309);
        SplitMix64 random = new SplitMix64(0x5345415442454c54L);

        for (int trial = 0; trial < 100; trial++) {
            int[] order = shuffledSequence(k + r, random);
            int erasedCount = random.nextInt(r + 1);
            boolean[] erased = new boolean[k + r];
            for (int i = 0; i < erasedCount; i++) {
                erased[order[i]] = true;
            }

            DecodeResult peeling = decodeResult(codec, encoded, erased,
                    DecodeOptions.defaults().withResidualSolve(false));
            assertEquals(peeling.residualUnknownCount(),
                    StructuralRecovery.residualUnknownCount(codec.parityCheckMatrix(), erased));

            int expected = decoderMargin(codec, encoded, order, k, r, residualLimit);
            assertEquals(expected,
                    StructuralRecovery.margin(codec.parityCheckMatrix(), order, residualLimit),
                    "bounded trial=" + trial);
        }
    }

    private static byte[][] encoded(LdpcStaircase codec, int k, int r, int seed) {
        SplitMix64 random = new SplitMix64(seed);
        byte[][] symbols = new byte[k + r][];
        for (int i = 0; i < k; i++) {
            symbols[i] = random.nextBytes(1);
        }
        codec.encode(symbols);
        return symbols;
    }

    private static boolean decode(LdpcStaircase codec, byte[][] encoded, boolean[] erased) {
        return decodeResult(codec, encoded, erased, DecodeOptions.defaults()).isComplete();
    }

    private static DecodeResult decodeResult(LdpcStaircase codec, byte[][] encoded,
            boolean[] erased, DecodeOptions options) {
        boolean[] present = new boolean[erased.length];
        byte[][] symbols = new byte[erased.length][];
        for (int i = 0; i < erased.length; i++) {
            present[i] = !erased[i];
            if (present[i]) {
                symbols[i] = encoded[i];
            }
        }
        return codec.decode(symbols, present, options);
    }

    private static int decoderMargin(LdpcStaircase codec, byte[][] encoded, int[] order, int k, int r) {
        return decoderMargin(codec, encoded, order, k, r, Integer.MAX_VALUE);
    }

    private static int decoderMargin(LdpcStaircase codec, byte[][] encoded, int[] order,
            int k, int r, int residualLimit) {
        int low = 0;
        int high = r;
        while (low < high) {
            int removed = (low + high + 1) >>> 1;
            boolean[] erased = new boolean[k + r];
            for (int i = 0; i < removed; i++) {
                erased[order[i]] = true;
            }
            DecodeResult result = decodeResult(codec, encoded, erased,
                    DecodeOptions.defaults().withMaxResidualUnknowns(residualLimit));
            if (result.isComplete()) {
                low = removed;
            } else {
                high = removed - 1;
            }
        }
        return r - low;
    }

    private static int[] shuffledSequence(int count, SplitMix64 random) {
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = i;
        }
        for (int i = values.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int temporary = values[i];
            values[i] = values[j];
            values[j] = temporary;
        }
        return values;
    }
}
