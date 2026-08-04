package io.github.barrrssss.ldpcstaircase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Measures the recovery margin {@code D}: how many symbols beyond {@code K} a decoder
 * actually needs under random and correlated physical loss patterns. Opt-in — this is a
 * measurement harness, not a regression test:
 *
 * <pre>./gradlew test --tests "*LossSimulation" -Dldpc.sim=200</pre>
 *
 * <p>The property's value is the trial count per configuration. The harness measures
 * {@code D} under random, contiguous-band, whole-unit, and mixed losses so a caller can
 * evaluate whether correlated damage concentrates into vulnerable graph regions.
 *
 * <p>Recovery is monotone in the received set — a superset of a sufficient set is
 * sufficient — so each trial binary-searches the removal order for the largest prefix
 * that still decodes. That costs about {@code log2(N)} decodes per trial instead of
 * {@code N}.
 *
 * <p>These numbers describe the codec on synthetic loss orders. They are <b>not</b> a
 * performance guarantee: the real margin depends on the transport's physical layout
 * and interleaving, which live outside this library.
 */
@EnabledIfSystemProperty(named = "ldpc.sim", matches = ".+")
class LossSimulation {

    private static final int SYMBOL_LENGTH = 64;
    private static final int PAGE_SIZE = 30; // codes per printed sheet, illustrative

    @Test
    void measureMarginAcrossLossModels() {
        int trials = Integer.getInteger("ldpc.sim", 100);
        int[][] configurations = {
                // K, R, N1
                {500, 250, 7},
                {2000, 1000, 7},
                {2000, 500, 7},
                {2000, 1000, 5},
        };

        System.out.println();
        System.out.printf("LDPC-Staircase recovery margin, %d trials per cell, S=%d%n",
                trials, SYMBOL_LENGTH);
        System.out.printf("%-22s %-18s %8s %8s %8s %8s%n",
                "config", "loss model", "meanD", "p95D", "maxD", "meanD%");

        for (int[] configuration : configurations) {
            int k = configuration[0];
            int r = configuration[1];
            int n1 = configuration[2];
            String label = String.format("K=%d R=%d N1=%d", k, r, n1);

            Fixture fixture = new Fixture(k, r, n1, 20260723);
            for (LossModel model : LossModel.values()) {
                int[] margins = new int[trials];
                for (int trial = 0; trial < trials; trial++) {
                    margins[trial] = fixture.measureMargin(model, trial);
                }

                Arrays.sort(margins);
                double mean = Arrays.stream(margins).average().orElse(0);
                int p95 = margins[Math.min(margins.length - 1, (int) (margins.length * 0.95))];
                int max = margins[margins.length - 1];
                System.out.printf("%-22s %-18s %8.2f %8d %8d %7.2f%%%n",
                        label, model.name().toLowerCase(), mean, p95, max, 100.0 * mean / k);
            }
        }

        System.out.println();
        System.out.println("D = received symbols beyond K needed for the last successful decode.");
    }

    /**
     * The order in which symbols are lost. Recovery is measured against the longest
     * prefix of this order the decoder survives.
     */
    private enum LossModel {
        /** Independent damage: any code anywhere. */
        RANDOM,

        /** One damaged band: a contiguous run of encoding symbol indices. */
        CONTIGUOUS_BAND,

        /** Whole lost sheets: contiguous groups of {@link #PAGE_SIZE} codes. */
        WHOLE_PAGE,

        /** Source-first: the pathological ordering for a systematic code. */
        SOURCE_FIRST,

        /** Repair-first: every repair symbol before any source symbol. */
        REPAIR_FIRST
    }

    private static final class Fixture {
        final LdpcStaircase codec;
        final byte[][] encoded;
        final int k;
        final int n;

        Fixture(int k, int r, int n1, int seed) {
            this.k = k;
            this.n = k + r;
            this.codec = LdpcStaircase.create(k, r, seed, n1);
            this.encoded = new byte[n][];
            SplitMix64 rng = new SplitMix64(seed);
            for (int i = 0; i < k; i++) {
                encoded[i] = rng.nextBytes(SYMBOL_LENGTH);
            }

            codec.encode(encoded);
        }

        /**
         * The margin for one trial: the number of received symbols beyond {@code K} at
         * the largest tolerable loss, found by binary search over the removal order.
         */
        int measureMargin(LossModel model, int trial) {
            int[] order = removalOrder(model, new SplitMix64(trial * 0x9E3779B9L + model.ordinal()));

            // Largest prefix length that still decodes. Losing everything past N-K is
            // hopeless for any code, so that bounds the search.
            int low = 0;
            int high = n - k;
            while (low < high) {
                int middle = (low + high + 1) >>> 1;
                if (decodes(order, middle)) {
                    low = middle;
                } else {
                    high = middle - 1;
                }
            }

            return (n - low) - k;
        }

        private boolean decodes(int[] order, int removed) {
            boolean[] present = new boolean[n];
            Arrays.fill(present, true);
            for (int i = 0; i < removed; i++) {
                present[order[i]] = false;
            }

            byte[][] symbols = new byte[n][];
            for (int i = 0; i < n; i++) {
                if (present[i]) {
                    symbols[i] = encoded[i];
                }
            }

            return codec.decode(symbols, present).isComplete();
        }

        private int[] removalOrder(LossModel model, SplitMix64 rng) {
            switch (model) {
                case RANDOM:
                    return shuffle(sequence(0, n), rng);
                case CONTIGUOUS_BAND: {
                    int start = rng.nextInt(n);
                    int[] order = new int[n];
                    for (int i = 0; i < n; i++) {
                        order[i] = (start + i) % n;
                    }

                    return order;
                }
                case WHOLE_PAGE: {
                    int pages = (n + PAGE_SIZE - 1) / PAGE_SIZE;
                    int[] pageOrder = shuffle(sequence(0, pages), rng);
                    int[] order = new int[n];
                    int next = 0;
                    for (int page : pageOrder) {
                        for (int i = page * PAGE_SIZE; i < Math.min(n, (page + 1) * PAGE_SIZE); i++) {
                            order[next++] = i;
                        }
                    }

                    return order;
                }
                case SOURCE_FIRST:
                    return concat(shuffle(sequence(0, k), rng), shuffle(sequence(k, n), rng));
                case REPAIR_FIRST:
                    return concat(shuffle(sequence(k, n), rng), shuffle(sequence(0, k), rng));
                default:
                    throw new IllegalStateException();
            }
        }
    }

    private static int[] sequence(int from, int toExclusive) {
        int[] values = new int[toExclusive - from];
        for (int i = 0; i < values.length; i++) {
            values[i] = from + i;
        }

        return values;
    }

    private static int[] shuffle(int[] values, SplitMix64 rng) {
        for (int i = values.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = values[i];
            values[i] = values[j];
            values[j] = tmp;
        }

        return values;
    }

    private static int[] concat(int[] first, int[] second) {
        List<Integer> all = new ArrayList<>(first.length + second.length);
        for (int value : first) {
            all.add(value);
        }
        for (int value : second) {
            all.add(value);
        }

        int[] result = new int[all.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = all.get(i);
        }

        return result;
    }
}
