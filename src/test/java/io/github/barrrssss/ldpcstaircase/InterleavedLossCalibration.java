package io.github.barrrssss.ldpcstaircase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Opt-in calibration of correlated loss through a stride-by-domain-count
 * interleaver.
 *
 * <p>Example:
 * <pre>
 * ./gradlew test --tests "*InterleavedLossCalibration" \
 *   -Dldpc.interleave=2000 -Dldpc.interleave.seeds=20 \
 *   -Dldpc.interleave.output=build/reports/interleaved-loss.csv
 * </pre>
 *
 * <p>Unlike {@link LossSimulation}, every correlated model is built in physical
 * print-slot order and then mapped through the exact writer permutation. Whole
 * pages and partial-page bands are indivisible failure domains: if one such domain
 * makes recovery fail, the reported margin is conservatively rounded to the state
 * before that domain was lost.
 */
@EnabledIfSystemProperty(named = "ldpc.interleave", matches = ".+")
class InterleavedLossCalibration {

    private static final int N1 = 7;
    private static final String DEFAULT_CONFIGS =
            "500:250:6,500:250:20,2000:500:20,2000:1000:20,"
            + "2000:1000:35,2000:1000:48,8000:4000:35,16000:8000:48";

    @Test
    void calibrateProductionInterleaver() throws IOException {
        Locale.setDefault(Locale.ROOT);
        int trials = positiveProperty("ldpc.interleave", 2_000);
        int graphSeeds = positiveProperty("ldpc.interleave.seeds", 20);
        List<Configuration> configurations = parseConfigurations(
                System.getProperty("ldpc.interleave.configs", DEFAULT_CONFIGS));

        List<String> rows = new ArrayList<>();
        rows.add("k,r,frames_per_page,page_count,n1,loss_model,graph_seeds,trials,"
                + "mean_d,d50,d90,d95,d99,max_d");
        long started = System.nanoTime();
        System.out.printf("%nProduction-interleaver loss calibration: %d trials/cell, %d seeds%n",
                trials, graphSeeds);
        System.out.printf("%-24s %-24s %8s %8s %8s %8s %8s %8s%n",
                "config", "loss model", "meanD", "D50", "D90", "D95", "D99", "maxD");

        for (Configuration configuration : configurations) {
            int scatteredD90 = -1;
            for (LossModel model : LossModel.values()) {
                int[][] marginsBySeed = new int[graphSeeds][];
                for (int seedIndex = 0; seedIndex < graphSeeds; seedIndex++) {
                    int seedTrials = trials / graphSeeds + (seedIndex < trials % graphSeeds ? 1 : 0);
                    int graphSeed = graphSeed(configuration, seedIndex);
                    ParityCheckMatrix matrix = ParityCheckMatrix.build(
                            configuration.k, configuration.r, N1, graphSeed);
                    final int ordinal = seedIndex;
                    marginsBySeed[seedIndex] = IntStream.range(0, seedTrials).parallel()
                            .map(trial -> {
                                Removal removal = removalOrder(configuration, model,
                                        captureSeed(configuration, model, ordinal, trial));
                                return StructuralRecovery.marginAtDomainBoundaries(
                                        matrix, removal.symbols, removal.domainStarts);
                            })
                            .toArray();
                }

                int[] combined = Arrays.stream(marginsBySeed).flatMapToInt(Arrays::stream).toArray();
                Summary summary = summarize(combined);
                if (model == LossModel.SCATTERED) {
                    scatteredD90 = summary.d90;
                } else if (model != LossModel.CONTIGUOUS_SLOTS_NATURAL_CONTROL
                        && summary.d90 > scatteredD90 + configuration.framesPerPage) {
                    throw new AssertionError(String.format(Locale.ROOT,
                            "%s D90 %d exceeds release reserve %d for K=%d R=%d F=%d",
                            model.label, summary.d90, scatteredD90 + configuration.framesPerPage,
                            configuration.k, configuration.r, configuration.framesPerPage));
                }
                rows.add(row(configuration, model, graphSeeds, summary, combined.length));
                System.out.printf("K=%-5d R=%-5d F=%-3d %-24s %8.3f %8d %8d %8d %8d %8d%n",
                        configuration.k, configuration.r, configuration.framesPerPage,
                        model.label, summary.mean, summary.d50, summary.d90,
                        summary.d95, summary.d99, summary.max);
            }
        }

        String output = System.getProperty("ldpc.interleave.output");
        if (output != null && !output.trim().isEmpty()) {
            Path path = Paths.get(output).toAbsolutePath().normalize();
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.write(path, rows, StandardCharsets.UTF_8);
            System.out.println("CSV: " + path);
        }
        System.out.printf("Total: %.2f seconds%n", (System.nanoTime() - started) / 1_000_000_000.0);
    }

    private enum LossModel {
        SCATTERED("scattered"),
        CONTIGUOUS_SLOTS_INTERLEAVED("contiguous-slots/interleaved"),
        WHOLE_PAGE_INTERLEAVED("whole-page/interleaved"),
        PARTIAL_PAGE_INTERLEAVED("partial-page/interleaved"),
        MIXED_INTERLEAVED("mixed/interleaved"),
        CONTIGUOUS_SLOTS_NATURAL_CONTROL("contiguous-slots/natural-control");

        final String label;

        LossModel(String label) {
            this.label = label;
        }
    }

    private static Removal removalOrder(Configuration configuration, LossModel model, long seed) {
        int n = configuration.n();
        SplitMix64 random = new SplitMix64(seed);
        int[] printOrder = model == LossModel.CONTIGUOUS_SLOTS_NATURAL_CONTROL
                ? naturalPrintSlots(n, configuration.framesPerPage)
                : interleavedPrintOrder(n, configuration.framesPerPage);

        switch (model) {
            case SCATTERED:
                return singletonDomains(shuffle(sequence(n), random));
            case CONTIGUOUS_SLOTS_INTERLEAVED:
            case CONTIGUOUS_SLOTS_NATURAL_CONTROL:
                return singletonDomains(contiguousPhysicalSlots(printOrder, random));
            case WHOLE_PAGE_INTERLEAVED:
                return groupedPhysicalDomains(printOrder, configuration.framesPerPage, random);
            case PARTIAL_PAGE_INTERLEAVED:
                return groupedPageBands(printOrder, configuration.framesPerPage, random);
            case MIXED_INTERLEAVED:
                return mixedPhysicalDomains(printOrder, configuration.framesPerPage, random);
            default:
                throw new IllegalStateException();
        }
    }

    /** Deterministic stride-by-domain-count writer permutation. */
    private static int[] interleavedPrintOrder(int symbolCount, int framesPerPage) {
        int pageCount = (symbolCount + framesPerPage - 1) / framesPerPage;
        int[] order = new int[pageCount * framesPerPage];
        Arrays.fill(order, -1);
        for (int page = 0; page < pageCount; page++) {
            for (int slotOnPage = 0; slotOnPage < framesPerPage; slotOnPage++) {
                int symbol = page + slotOnPage * pageCount;
                if (symbol < symbolCount) {
                    order[page * framesPerPage + slotOnPage] = symbol;
                }
            }
        }
        return order;
    }

    private static int[] naturalPrintSlots(int symbolCount, int framesPerPage) {
        int pageCount = (symbolCount + framesPerPage - 1) / framesPerPage;
        int[] slots = new int[pageCount * framesPerPage];
        Arrays.fill(slots, -1);
        for (int symbol = 0; symbol < symbolCount; symbol++) {
            slots[symbol] = symbol;
        }
        return slots;
    }

    private static Removal groupedPhysicalDomains(int[] printOrder, int domainSize, SplitMix64 random) {
        int domainCount = (printOrder.length + domainSize - 1) / domainSize;
        int[] domainOrder = shuffle(sequence(domainCount), random);
        int symbolCount = (int) Arrays.stream(printOrder).filter(value -> value >= 0).count();
        int[] symbols = new int[symbolCount];
        int[] starts = new int[domainCount];
        int next = 0;
        for (int d = 0; d < domainOrder.length; d++) {
            starts[d] = next;
            int physicalDomain = domainOrder[d];
            int from = physicalDomain * domainSize;
            int to = Math.min(printOrder.length, from + domainSize);
            int[] slots = sequence(from, to);
            shuffle(slots, random);
            for (int slot : slots) {
                if (printOrder[slot] >= 0) {
                    symbols[next++] = printOrder[slot];
                }
            }
        }
        return new Removal(symbols, starts);
    }

    private static Removal groupedPageBands(int[] printOrder, int framesPerPage, SplitMix64 random) {
        int pageCount = (printOrder.length + framesPerPage - 1) / framesPerPage;
        List<int[]> domains = new ArrayList<>();
        for (int page = 0; page < pageCount; page++) {
            int pageStart = page * framesPerPage;
            int pageEnd = Math.min(printOrder.length, pageStart + framesPerPage);
            int pageLength = pageEnd - pageStart;
            int bandSize = Math.max(1, (pageLength + 3) / 4);
            for (int start = pageStart; start < pageEnd; start += bandSize) {
                int end = Math.min(pageEnd, start + bandSize);
                int[] domain = new int[end - start];
                for (int i = 0; i < domain.length; i++) {
                    domain[i] = printOrder[start + i];
                }
                domains.add(domain);
            }
        }

        for (int i = domains.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int[] temporary = domains.get(i);
            domains.set(i, domains.get(j));
            domains.set(j, temporary);
        }
        int symbolCount = (int) Arrays.stream(printOrder).filter(value -> value >= 0).count();
        int[] symbols = new int[symbolCount];
        int[] starts = new int[domains.size()];
        int next = 0;
        for (int d = 0; d < domains.size(); d++) {
            starts[d] = next;
            int[] domain = domains.get(d);
            shuffle(domain, random);
            for (int symbol : domain) {
                if (symbol >= 0) {
                    symbols[next++] = symbol;
                }
            }
        }
        return new Removal(symbols, starts);
    }

    private static int[] contiguousPhysicalSlots(int[] printOrder, SplitMix64 random) {
        int symbolCount = (int) Arrays.stream(printOrder).filter(value -> value >= 0).count();
        int[] symbols = new int[symbolCount];
        int start = random.nextInt(printOrder.length);
        int next = 0;
        for (int offset = 0; offset < printOrder.length; offset++) {
            int symbol = printOrder[(start + offset) % printOrder.length];
            if (symbol >= 0) {
                symbols[next++] = symbol;
            }
        }
        return symbols;
    }

    /**
     * Deterministic mixture of whole-page loss, quarter-page bands, and isolated
     * damaged codes. Domains are sampled without replacement until every slot is
     * covered, preserving the physical-event boundary for conservative margins.
     */
    private static Removal mixedPhysicalDomains(int[] printOrder, int framesPerPage, SplitMix64 random) {
        int physicalSlots = printOrder.length;
        int n = (int) Arrays.stream(printOrder).filter(value -> value >= 0).count();
        boolean[] used = new boolean[physicalSlots];
        for (int slot = 0; slot < physicalSlots; slot++) {
            used[slot] = printOrder[slot] < 0;
        }
        List<int[]> domains = new ArrayList<>();
        int remaining = n;
        int bandSize = Math.max(1, (framesPerPage + 3) / 4);
        while (remaining > 0) {
            int choice = random.nextInt(10);
            int start;
            int length;
            if (choice < 3) {
                int page = random.nextInt(physicalSlots / framesPerPage);
                start = page * framesPerPage;
                length = framesPerPage;
            } else if (choice < 7) {
                int page = random.nextInt(physicalSlots / framesPerPage);
                int pageStart = page * framesPerPage;
                int pageLength = framesPerPage;
                start = pageStart + random.nextInt(pageLength);
                length = Math.min(bandSize, pageStart + pageLength - start);
            } else {
                start = random.nextInt(physicalSlots);
                length = 1;
            }

            int[] candidate = new int[length];
            int count = 0;
            for (int slot = start; slot < start + length; slot++) {
                if (!used[slot]) {
                    used[slot] = true;
                    if (printOrder[slot] >= 0) {
                        candidate[count++] = printOrder[slot];
                        remaining--;
                    }
                }
            }
            if (count > 0) {
                domains.add(Arrays.copyOf(candidate, count));
            }
        }

        // Event chronology is itself random; construction order only chose coverage.
        for (int i = domains.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int[] temporary = domains.get(i);
            domains.set(i, domains.get(j));
            domains.set(j, temporary);
        }

        int[] symbols = new int[n];
        int[] starts = new int[domains.size()];
        int next = 0;
        for (int d = 0; d < domains.size(); d++) {
            starts[d] = next;
            int[] domain = domains.get(d);
            shuffle(domain, random);
            for (int symbol : domain) {
                symbols[next++] = symbol;
            }
        }
        return new Removal(symbols, starts);
    }

    private static Removal singletonDomains(int[] symbols) {
        return new Removal(symbols, sequence(symbols.length));
    }

    private static String row(Configuration configuration, LossModel model, int graphSeeds,
            Summary summary, int trials) {
        return String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%s,%d,%d,%.6f,%d,%d,%d,%d,%d",
                configuration.k, configuration.r, configuration.framesPerPage,
                configuration.pageCount(), N1, model.label, graphSeeds, trials,
                summary.mean, summary.d50, summary.d90, summary.d95, summary.d99, summary.max);
    }

    private static Summary summarize(int[] values) {
        int[] sorted = values.clone();
        Arrays.sort(sorted);
        return new Summary(Arrays.stream(sorted).average().orElse(0),
                quantile(sorted, 0.50), quantile(sorted, 0.90), quantile(sorted, 0.95),
                quantile(sorted, 0.99), sorted[sorted.length - 1]);
    }

    private static int quantile(int[] sorted, double probability) {
        int index = Math.max(0, (int) Math.ceil(probability * sorted.length) - 1);
        return sorted[Math.min(index, sorted.length - 1)];
    }

    private static List<Configuration> parseConfigurations(String text) {
        List<Configuration> result = new ArrayList<>();
        for (String item : text.split(",")) {
            String[] parts = item.trim().split(":");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Configuration must be K:R:F; got " + item);
            }
            Configuration configuration = new Configuration(Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            if (configuration.k < 1 || configuration.r < N1 || configuration.framesPerPage < 1) {
                throw new IllegalArgumentException("Configuration requires K>=1, R>=7, F>=1; got " + item);
            }
            result.add(configuration);
        }
        return result;
    }

    private static int[] sequence(int count) {
        return sequence(0, count);
    }

    private static int[] sequence(int from, int toExclusive) {
        int[] values = new int[toExclusive - from];
        for (int i = 0; i < values.length; i++) {
            values[i] = from + i;
        }
        return values;
    }

    private static int[] shuffle(int[] values, SplitMix64 random) {
        for (int i = values.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int temporary = values[i];
            values[i] = values[j];
            values[j] = temporary;
        }
        return values;
    }

    private static int graphSeed(Configuration configuration, int seedIndex) {
        long mixed = mix64((((long) configuration.k) << 32) ^ configuration.r
                ^ ((long) configuration.framesPerPage << 16) ^ seedIndex);
        return (int) (Long.remainderUnsigned(mixed, 2_147_483_646L) + 1L);
    }

    private static long captureSeed(Configuration configuration, LossModel model,
            int seedIndex, int trial) {
        return mix64((((long) configuration.k) << 32) ^ configuration.r
                ^ ((long) configuration.framesPerPage << 16)
                ^ ((long) model.ordinal() << 56) ^ ((long) seedIndex << 48) ^ trial);
    }

    private static long mix64(long value) {
        value += 0x9E3779B97F4A7C15L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static int positiveProperty(String name, int fallback) {
        int value = Integer.getInteger(name, fallback);
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static final class Configuration {
        final int k;
        final int r;
        final int framesPerPage;

        Configuration(int k, int r, int framesPerPage) {
            this.k = k;
            this.r = r;
            this.framesPerPage = framesPerPage;
        }

        int n() {
            return k + r;
        }

        int pageCount() {
            return (n() + framesPerPage - 1) / framesPerPage;
        }
    }

    private static final class Removal {
        final int[] symbols;
        final int[] domainStarts;

        Removal(int[] symbols, int[] domainStarts) {
            this.symbols = symbols;
            this.domainStarts = domainStarts;
        }
    }

    private static final class Summary {
        final double mean;
        final int d50;
        final int d90;
        final int d95;
        final int d99;
        final int max;

        Summary(double mean, int d50, int d90, int d95, int d99, int max) {
            this.mean = mean;
            this.d50 = d50;
            this.d90 = d90;
            this.d95 = d95;
            this.d99 = d99;
            this.max = max;
        }
    }
}
