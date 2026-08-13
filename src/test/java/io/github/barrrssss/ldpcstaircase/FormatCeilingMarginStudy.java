package io.github.barrrssss.ldpcstaircase;

import java.util.Random;

/**
 * Test-only D90 study for shapes at the transport u16 total-symbol ceiling, where {@code R} exceeds the
 * decoder's residual-solve bound.
 *
 * <p>Inside the calibrated envelope ({@code R <= 8192}) the caller's bound cannot change
 * recoverability, because at {@code K} received symbols no more than {@code R} columns are erased.
 * Past it the bound becomes the binding condition: a received set can be mathematically sufficient
 * yet still refused, because the system left after peeling is wider than the decoder will solve.
 *
 * <p>This measures the margin that bound actually costs. For each trial it shuffles all
 * {@code K + R} symbols, then binary-searches the largest prefix that can be erased while the
 * post-peeling residual stays within {@code maxResidualUnknowns}. The rank oracle is deliberately
 * not consulted: with {@code R} far above the bound the width condition binds first, and the rank
 * pass is quadratic in {@code R}.
 *
 * <p>Run with {@code ./gradlew runFormatCeilingMarginStudy}.
 */
public final class FormatCeilingMarginStudy {

    private static final int N1 = ParityCheckMatrix.DEFAULT_LEFT_DEGREE;
    private static final int BOUND = 8192;

    public static void main(String[] args) {
        int k = args.length > 0 ? Integer.parseInt(args[0]) : 46_811;
        int r = args.length > 1 ? Integer.parseInt(args[1]) : 18_725;
        int seeds = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        int trialsPerSeed = args.length > 3 ? Integer.parseInt(args[3]) : 40;
        int framesPerPage = args.length > 4 ? Integer.parseInt(args[4]) : 0;

        int n = k + r;
        System.out.printf("K=%d R=%d N=%d  N1=%d  residual bound=%d%n", k, r, n, N1, BOUND);
        System.out.printf("seeds=%d trials/seed=%d (%d trials)%n%n", seeds, trialsPerSeed, seeds * trialsPerSeed);

        int[] margins = new int[seeds * trialsPerSeed];
        int at = 0;
        long start = System.currentTimeMillis();

        for (int seedIndex = 0; seedIndex < seeds; seedIndex++) {
            int graphSeed = 1 + seedIndex * 7_919;
            ParityCheckMatrix matrix = ParityCheckMatrix.build(k, r, N1, graphSeed);
            Random random = new Random(0xC0FFEEL + seedIndex);

            int seedWorst = 0;
            for (int trial = 0; trial < trialsPerSeed; trial++) {
                int[] order = shuffled(n, random);
                int margin = boundedMargin(matrix, order, r, n);
                margins[at++] = margin;
                seedWorst = Math.max(seedWorst, margin);
            }
            System.out.printf("  graph seed %-8d worst margin in %d trials: %d%n",
                    graphSeed, trialsPerSeed, seedWorst);
        }

        java.util.Arrays.sort(margins);
        System.out.printf("%nelapsed %.1f s%n", (System.currentTimeMillis() - start) / 1000.0);
        System.out.println("\npercentile | additional symbols D | received count | % of all printed");
        for (double p : new double[] {0.50, 0.90, 0.95, 0.99, 1.00}) {
            int idx = Math.min(margins.length - 1, (int) Math.ceil(p * margins.length) - 1);
            int d = margins[Math.max(0, idx)];
            System.out.printf("D%-9s | %20d | %14d | %.1f%%%n",
                    (int) (p * 100), d, k + d, 100.0 * (k + d) / n);
        }

        if (framesPerPage > 0) {
            correlatedStudy(k, r, n, framesPerPage, seeds, trialsPerSeed,
                    margins[(int) Math.ceil(0.90 * margins.length) - 1]);
        }

        int guaranteed = r - BOUND;
        System.out.printf("%nunconditional safe point (missing <= bound): D = R - %d = %d, received %d (%.1f%%)%n",
                BOUND, guaranteed, k + guaranteed, 100.0 * (k + guaranteed) / n);
        System.out.printf("tolerated missing codes at D90: %d of %d printed (%.1f%%)%n",
                r - margins[(int) Math.ceil(0.90 * margins.length) - 1], n,
                100.0 * (r - margins[(int) Math.ceil(0.90 * margins.length) - 1]) / n);
    }

    /**
     * Gate-8 equivalent under the residual bound: correlated physical loss through the normative
     * blank-preserving page interleaver, with whole pages and page bands as indivisible failure
     * domains. The gate-8 harness measures the *rank* margin, which is not what binds once
     * {@code R > BOUND}; this repeats it against the bound instead.
     */
    private static void correlatedStudy(int k, int r, int n, int framesPerPage, int seeds,
            int trialsPerSeed, int scatteredD90) {
        System.out.printf("%n=== correlated physical loss, F=%d frames/page, %d pages ===%n",
                framesPerPage, (n + framesPerPage - 1) / framesPerPage);
        System.out.println("model                    |    D50 |    D90 |   maxD | received at D90 | vs reserve");

        for (String model : new String[] {"whole-page", "page-band", "contiguous-slots"}) {
            int[] margins = new int[seeds * trialsPerSeed];
            int at = 0;
            for (int seedIndex = 0; seedIndex < seeds; seedIndex++) {
                ParityCheckMatrix matrix = ParityCheckMatrix.build(k, r, N1, 1 + seedIndex * 7_919);
                Random random = new Random(0xBEEFL + seedIndex);
                for (int trial = 0; trial < trialsPerSeed; trial++) {
                    int[][] domains = physicalDomains(n, framesPerPage, model, random);
                    margins[at++] = boundedDomainMargin(matrix, domains, r, n);
                }
            }
            java.util.Arrays.sort(margins);
            int d50 = margins[(int) Math.ceil(0.50 * margins.length) - 1];
            int d90 = margins[(int) Math.ceil(0.90 * margins.length) - 1];
            int max = margins[margins.length - 1];
            int reserve = scatteredD90 + framesPerPage;
            System.out.printf("%-24s | %6d | %6d | %6d | %14.1f%% | %s%n",
                    model, d50, d90, max, 100.0 * (k + d90) / n,
                    d90 <= reserve ? "within (" + reserve + ")" : "EXCEEDS " + reserve);
        }
    }

    /** Physical failure domains in print order, mapped through the normative interleaver. */
    private static int[][] physicalDomains(int n, int framesPerPage, String model, Random random) {
        int pageCount = (n + framesPerPage - 1) / framesPerPage;
        int[] printOrder = new int[pageCount * framesPerPage];
        java.util.Arrays.fill(printOrder, -1);
        for (int page = 0; page < pageCount; page++) {
            for (int slot = 0; slot < framesPerPage; slot++) {
                int symbol = page + slot * pageCount;
                if (symbol < n) {
                    printOrder[page * framesPerPage + slot] = symbol;
                }
            }
        }

        int domainSize;
        if ("whole-page".equals(model)) {
            domainSize = framesPerPage;
        } else if ("page-band".equals(model)) {
            domainSize = Math.max(1, (framesPerPage + 3) / 4);
        } else {
            domainSize = 1;
        }

        int domainCount = (printOrder.length + domainSize - 1) / domainSize;
        int[] order = new int[domainCount];
        for (int i = 0; i < domainCount; i++) {
            order[i] = i;
        }
        for (int i = domainCount - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = order[i];
            order[i] = order[j];
            order[j] = tmp;
        }

        int[][] domains = new int[domainCount][];
        for (int d = 0; d < domainCount; d++) {
            int from = order[d] * domainSize;
            int to = Math.min(printOrder.length, from + domainSize);
            int count = 0;
            for (int slot = from; slot < to; slot++) {
                if (printOrder[slot] >= 0) {
                    count++;
                }
            }
            int[] symbols = new int[count];
            int at = 0;
            for (int slot = from; slot < to; slot++) {
                if (printOrder[slot] >= 0) {
                    symbols[at++] = printOrder[slot];
                }
            }
            domains[d] = symbols;
        }
        return domains;
    }

    /**
     * Margin rounded to the last whole failure domain that still decodes within the bound: losing
     * part of a page is not a state paper can be in.
     */
    private static int boundedDomainMargin(ParityCheckMatrix matrix, int[][] domains, int r, int n) {
        int low = 0;
        int high = domains.length;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            boolean[] erased = new boolean[n];
            int erasedCount = 0;
            for (int d = 0; d < mid && erasedCount <= r; d++) {
                for (int symbol : domains[d]) {
                    if (!erased[symbol]) {
                        erased[symbol] = true;
                        erasedCount++;
                    }
                }
            }
            if (erasedCount <= r && StructuralRecovery.residualUnknownCount(matrix, erased) <= BOUND) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }

        boolean[] erased = new boolean[n];
        int erasedCount = 0;
        for (int d = 0; d < low; d++) {
            for (int symbol : domains[d]) {
                if (!erased[symbol]) {
                    erased[symbol] = true;
                    erasedCount++;
                }
            }
        }
        return r - erasedCount;
    }

    /** Largest prefix erasable while the post-peeling residual stays within {@link #BOUND}. */
    private static int boundedMargin(ParityCheckMatrix matrix, int[] order, int r, int n) {
        int low = 0;
        int high = r;
        while (low < high) {
            int removed = (low + high + 1) >>> 1;
            boolean[] erased = new boolean[n];
            for (int i = 0; i < removed; i++) {
                erased[order[i]] = true;
            }
            if (StructuralRecovery.residualUnknownCount(matrix, erased) <= BOUND) {
                low = removed;
            } else {
                high = removed - 1;
            }
        }
        return r - low;
    }

    private static int[] shuffled(int n, Random random) {
        int[] values = new int[n];
        for (int i = 0; i < n; i++) {
            values[i] = i;
        }
        for (int i = n - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = values[i];
            values[i] = values[j];
            values[j] = tmp;
        }
        return values;
    }

    private FormatCeilingMarginStudy() {
    }
}
