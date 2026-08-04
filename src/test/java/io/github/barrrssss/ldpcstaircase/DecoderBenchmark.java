package io.github.barrrssss.ldpcstaircase;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Times matrix construction, encoding, and decoding up to the shared specification's motivating case
 * ({@code K = 10,000}, {@code R = 5,000}, {@code S = 818}). Opt-in — this is a
 * measurement harness, not a regression test:
 *
 * <pre>./gradlew test --tests "*DecoderBenchmark" -Dldpc.bench=1</pre>
 *
 * <p>Output feeds the shared specification's validation gate 5 (benchmark generation time and peak RAM
 * through {@code K = 10,000}). Times are wall clock on one warmed JVM; the heap figures
 * are a coarse allocated-bytes delta around each phase, not a real peak-RSS measurement,
 * so treat them as an order of magnitude rather than the seatbelt input.
 */
@EnabledIfSystemProperty(named = "ldpc.bench", matches = ".+")
class DecoderBenchmark {

    private static final int SYMBOL_LENGTH = 818; // the V32-H QR payload of the shared specification

    /** The physical Android restore reported on 2026-08-04 (QR V10-L, 100% redundancy). */
    @Test
    void benchmarkAndroidRestoreShape() {
        int k = 2_939;
        int r = 2_939;
        int received = 2_979;
        int symbolLength = 248;

        LdpcStaircase codec = LdpcStaircase.create(k, r, 123_456_789, 7);
        byte[][] encoded = encodedSymbols(codec, k, r, symbolLength, 0xB1A4_2026L);

        // Warm the JVM before the reported measurements. Android device measurements
        // use a separate instrumentation harness; this catches desktop regressions.
        decode(codec, encoded, k + r, k + r - received, 0);

        System.out.println();
        System.out.printf("Android restore shape: K=%d R=%d S=%d received=%d%n",
                k, r, symbolLength, received);
        System.out.printf("%8s %12s %12s %12s%n", "seed", "decode ms", "residual u", "complete");
        for (int seed = 1; seed <= 5; seed++) {
            DecodeRun run = decode(codec, encoded, k + r, k + r - received, seed);
            System.out.printf("%8d %12d %12d %12s%n", seed, run.millis,
                    run.result.residualUnknownCount(), run.result.isComplete());
        }
    }

    @Test
    void benchmarkAcrossSizes() {
        int[] sizes = {1_000, 2_500, 5_000, 10_000};

        System.out.println();
        System.out.printf("LDPC-Staircase, N1=7, S=%d, R=K/2%n", SYMBOL_LENGTH);
        System.out.printf("%8s %8s %10s %10s %12s %12s %12s%n",
                "K", "R", "build ms", "encode ms", "peel ms", "residual ms", "residual u");

        for (int k : sizes) {
            int r = k / 2;
            run(k, r);
        }

        System.out.println();
        System.out.println("peel ms: decode with R/2 random losses (expected to peel cleanly).");
        System.out.println("residual ms: decode at the full R-symbol loss budget.");
    }

    private static void run(int k, int r) {
        int n = k + r;

        long buildStart = System.nanoTime();
        LdpcStaircase codec = LdpcStaircase.create(k, r, 20260723, 7);
        long buildMs = millis(buildStart);

        long encodeStart = System.nanoTime();
        byte[][] encoded = encodedSymbols(codec, k, r, SYMBOL_LENGTH, k);
        long encodeMs = millis(encodeStart);

        DecodeRun light = decode(codec, encoded, n, r / 2, 1);
        DecodeRun heavy = decode(codec, encoded, n, r, 2);

        System.out.printf("%8d %8d %10d %10d %12d %12d %12d%n",
                k, r, buildMs, encodeMs, light.millis, heavy.millis, heavy.result.residualUnknownCount());

        if (!light.result.isComplete()) {
            System.out.println("  note: the R/2 loss decode did not complete: " + light.result);
        }

        if (!heavy.result.isComplete()) {
            System.out.println("  note: the full-budget decode did not complete (expected near the "
                    + "threshold): " + heavy.result);
        }
    }

    private static byte[][] encodedSymbols(LdpcStaircase codec, int k, int r, int symbolLength,
            long dataSeed) {
        byte[][] encoded = new byte[k + r][];
        SplitMix64 rng = new SplitMix64(dataSeed);
        for (int i = 0; i < k; i++) {
            encoded[i] = rng.nextBytes(symbolLength);
        }

        codec.encode(encoded);
        return encoded;
    }

    private static DecodeRun decode(LdpcStaircase codec, byte[][] encoded, int n, int lossCount,
            long lossSeed) {
        boolean[] present = new boolean[n];
        Arrays.fill(present, true);
        SplitMix64 rng = new SplitMix64(lossSeed);
        int removed = 0;
        while (removed < lossCount) {
            int index = rng.nextInt(n);
            if (present[index]) {
                present[index] = false;
                removed++;
            }
        }

        byte[][] symbols = new byte[n][];
        for (int i = 0; i < n; i++) {
            if (present[i]) {
                symbols[i] = encoded[i].clone();
            }
        }

        long start = System.nanoTime();
        DecodeResult result = codec.decode(symbols, present);
        return new DecodeRun(result, millis(start));
    }

    private static long millis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static final class DecodeRun {
        final DecodeResult result;
        final long millis;

        DecodeRun(DecodeResult result, long millis) {
            this.result = result;
            this.millis = millis;
        }
    }
}
