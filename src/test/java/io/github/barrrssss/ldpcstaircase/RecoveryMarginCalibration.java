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
 * Opt-in calibration harness for the scattered-capture recovery margin {@code D}.
 *
 * <p>Example:
 * <pre>
 * ./gradlew test --tests "*RecoveryMarginCalibration" \
 *   -Dldpc.calibrate=2000 -Dldpc.calibrate.seeds=20 \
 *   -Dldpc.calibrate.configs="1000:500,1000:1000,2939:2939" \
 *   -Dldpc.calibrate.output="build/reports/recovery-margin.csv"
 * </pre>
 *
 * <p>The trial count is per configuration and is spread as evenly as possible over
 * the requested graph seeds. Each trial shuffles all {@code K + R} symbol indices;
 * every prefix is therefore a uniform scattered erasure set. The structural rank
 * oracle finds the exact margin for that nested removal order in one pass.
 */
@EnabledIfSystemProperty(named = "ldpc.calibrate", matches = ".+")
class RecoveryMarginCalibration {

    private static final int N1 = 7;
    private static final String DEFAULT_CONFIGS =
            "256:38,256:64,256:128,256:192,256:256,"
            + "500:75,500:125,500:250,500:375,500:500,"
            + "1000:150,1000:250,1000:500,1000:750,1000:1000,"
            + "2000:300,2000:500,2000:1000,2000:1500,2000:2000,"
            + "4000:600,4000:1000,4000:2000,4000:3000,4000:4000,"
            + "8000:1200,8000:2000,8000:4000,8000:6000,8000:8000,"
            + "2939:2939";

    @Test
    void calibrate() throws IOException {
        Locale.setDefault(Locale.ROOT);
        int trials = positiveProperty("ldpc.calibrate", 2_000);
        int graphSeeds = positiveProperty("ldpc.calibrate.seeds", 20);
        int maxResidualUnknowns = nonNegativeProperty(
                "ldpc.calibrate.maxResidualUnknowns", Integer.MAX_VALUE);
        List<Configuration> configurations = parseConfigurations(
                System.getProperty("ldpc.calibrate.configs", DEFAULT_CONFIGS));

        long started = System.nanoTime();
        List<String> rows = new ArrayList<>();
        rows.add("scope,k,r,repair_ratio,n1,graph_seed,graph_seeds,trials,mean_d,d50,d90,d95,d99,max_d,success_before_d90,success_at_d90");

        System.out.printf("%nLDPC scattered-capture calibration: %d trials/config, %d seeds, N1=%d, residual limit=%s%n",
                trials, graphSeeds, N1,
                maxResidualUnknowns == Integer.MAX_VALUE ? "unbounded" : Integer.toString(maxResidualUnknowns));
        System.out.printf("%-15s %8s %8s %8s %8s %8s %8s %8s%n",
                "config", "meanD", "D50", "D90", "D95", "D99", "maxD", "seconds");

        for (Configuration configuration : configurations) {
            long cellStarted = System.nanoTime();
            int[][] marginsBySeed = new int[graphSeeds][];
            for (int seedIndex = 0; seedIndex < graphSeeds; seedIndex++) {
                int seedTrials = trials / graphSeeds + (seedIndex < trials % graphSeeds ? 1 : 0);
                int graphSeed = graphSeed(configuration, seedIndex);
                ParityCheckMatrix matrix = ParityCheckMatrix.build(
                        configuration.k, configuration.r, N1, graphSeed);
                final int ordinal = seedIndex;
                marginsBySeed[seedIndex] = IntStream.range(0, seedTrials).parallel()
                        .map(trial -> StructuralRecovery.margin(matrix,
                                shuffledSequence(configuration.n(),
                                        captureSeed(configuration, ordinal, trial)),
                                maxResidualUnknowns))
                        .toArray();
                rows.add(row("seed", configuration, graphSeed, 1, marginsBySeed[seedIndex]));
            }

            int[] combined = Arrays.stream(marginsBySeed)
                    .flatMapToInt(Arrays::stream)
                    .toArray();
            Summary summary = summarize(combined);
            rows.add(row("combined", configuration, 0, graphSeeds, combined));
            System.out.printf("K=%-5d R=%-5d %8.3f %8d %8d %8d %8d %8d %8.2f%n",
                    configuration.k, configuration.r, summary.mean, summary.d50, summary.d90,
                    summary.d95, summary.d99, summary.max,
                    (System.nanoTime() - cellStarted) / 1_000_000_000.0);
        }

        String output = System.getProperty("ldpc.calibrate.output");
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

    private static String row(String scope, Configuration configuration, int graphSeed,
            int graphSeeds, int[] margins) {
        Summary summary = summarize(margins);
        return String.format(Locale.ROOT,
                "%s,%d,%d,%.8f,%d,%d,%d,%d,%.6f,%d,%d,%d,%d,%d,%.8f,%.8f",
                scope, configuration.k, configuration.r, (double) configuration.r / configuration.k,
                N1, graphSeed, graphSeeds, margins.length, summary.mean, summary.d50, summary.d90,
                summary.d95, summary.d99, summary.max, summary.successBeforeD90, summary.successAtD90);
    }

    private static Summary summarize(int[] values) {
        int[] sorted = values.clone();
        Arrays.sort(sorted);
        int d90 = quantile(sorted, 0.90);
        return new Summary(
                Arrays.stream(sorted).average().orElse(0),
                quantile(sorted, 0.50),
                d90,
                quantile(sorted, 0.95),
                quantile(sorted, 0.99),
                sorted[sorted.length - 1],
                (double) upperBound(sorted, d90 - 1) / sorted.length,
                (double) upperBound(sorted, d90) / sorted.length);
    }

    private static int quantile(int[] sorted, double probability) {
        int index = Math.max(0, (int) Math.ceil(probability * sorted.length) - 1);
        return sorted[Math.min(index, sorted.length - 1)];
    }

    private static int upperBound(int[] sorted, int value) {
        int low = 0;
        int high = sorted.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (sorted[middle] <= value) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static int[] shuffledSequence(int count, long seed) {
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = i;
        }
        SplitMix64 random = new SplitMix64(seed);
        for (int i = values.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int temporary = values[i];
            values[i] = values[j];
            values[j] = temporary;
        }
        return values;
    }

    private static int graphSeed(Configuration configuration, int seedIndex) {
        long mixed = mix64((((long) configuration.k) << 32) ^ configuration.r ^ seedIndex);
        return (int) (Long.remainderUnsigned(mixed, 2_147_483_646L) + 1L);
    }

    private static long captureSeed(Configuration configuration, int seedIndex, int trial) {
        return mix64((((long) configuration.k) << 32) ^ configuration.r
                ^ ((long) seedIndex << 48) ^ trial ^ 0x43415054555245L);
    }

    private static long mix64(long value) {
        value += 0x9E3779B97F4A7C15L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static List<Configuration> parseConfigurations(String text) {
        List<Configuration> result = new ArrayList<>();
        for (String item : text.split(",")) {
            String[] parts = item.trim().split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Configuration must be K:R; got " + item);
            }
            int k = Integer.parseInt(parts[0]);
            int r = Integer.parseInt(parts[1]);
            if (k < 1 || r < N1) {
                throw new IllegalArgumentException("Configuration requires K >= 1 and R >= N1; got " + item);
            }
            result.add(new Configuration(k, r));
        }
        return result;
    }

    private static int positiveProperty(String name, int fallback) {
        int value = Integer.getInteger(name, fallback);
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int nonNegativeProperty(String name, int fallback) {
        int value = Integer.getInteger(name, fallback);
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return value;
    }

    private static final class Configuration {
        final int k;
        final int r;

        Configuration(int k, int r) {
            this.k = k;
            this.r = r;
        }

        int n() {
            return k + r;
        }
    }

    private static final class Summary {
        final double mean;
        final int d50;
        final int d90;
        final int d95;
        final int d99;
        final int max;
        final double successBeforeD90;
        final double successAtD90;

        Summary(double mean, int d50, int d90, int d95, int d99, int max,
                double successBeforeD90, double successAtD90) {
            this.mean = mean;
            this.d50 = d50;
            this.d90 = d90;
            this.d95 = d95;
            this.d99 = d99;
            this.max = max;
            this.successBeforeD90 = successBeforeD90;
            this.successAtD90 = successAtD90;
        }
    }
}
