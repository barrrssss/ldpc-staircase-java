package io.github.barrrssss.ldpcstaircase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The independent-oracle gate of specification decision 0002.
 *
 * <p>{@link ConformanceTest} proves this implementation reproduces the committed
 * vectors, which the Java {@code VectorGenerator} also produced. That leaves one
 * failure mode open: the generator and both implementations could share a single
 * misreading of {@code SPEC.md}. The specification repository therefore carries a
 * dependency-free Python reference checker, derived from the specification rather
 * than from either implementation, plus a committed differential corpus at
 * {@code reference/corpus.json}.
 *
 * <p>This test runs that corpus through the Java codec and compares every field with
 * the Python checker's own answer for the same case. The checker is invoked as an
 * ordinary subprocess and writes its results into the Gradle build directory; nothing
 * here imports it, and the corpus is not a conformance vector set.
 *
 * <p>When no Python 3.11+ interpreter is on the machine the corpus cases are aborted
 * rather than failed, so an ordinary developer build still runs. Set
 * {@code -Dldpc.referenceCorpus.required=true} — or the environment variable
 * {@code LDPC_REFERENCE_CORPUS_REQUIRED=1} — to turn a missing interpreter into a
 * failure. The pre-release checklist uses the required form.
 */
class ReferenceCorpusTest {

    private static final Path SPEC_DIR =
            Paths.get("external", "ldpc-staircase-spec").toAbsolutePath();
    private static final Path CHECKER = SPEC_DIR.resolve("reference/ldpc_staircase_reference.py");
    private static final Path CORPUS = SPEC_DIR.resolve("reference/corpus.json");
    private static final Path RESULTS =
            Paths.get("build", "reference-corpus", "corpus-results.json").toAbsolutePath();

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    private static List<Map<String, Object>> expectedCases;
    private static Map<String, int[]> lossSets;
    private static String unavailableReason;

    @BeforeAll
    static void runReferenceChecker() {
        if (!Files.exists(CHECKER) || !Files.exists(CORPUS)) {
            unavailableReason = "The reference checker is not present at " + CHECKER
                    + ". Update the external/ldpc-staircase-spec submodule to a revision"
                    + " that contains reference/ (specification decision 0002).";
            return;
        }

        String interpreter = findPython();
        if (interpreter == null) {
            unavailableReason = "No Python 3.11+ interpreter was found on PATH.";
            return;
        }

        try {
            Files.createDirectories(RESULTS.getParent());
            int exitCode = run(interpreter, CHECKER.toString(), "emit-corpus", CORPUS.toString(),
                    "--out", RESULTS.toString());
            if (exitCode != 0) {
                fail("The reference checker failed with exit code " + exitCode
                        + " while emitting " + CORPUS);
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Could not run the reference checker", e);
        }

        Map<String, Object> document =
                Json.asMap(Json.parse(read(RESULTS)));
        assertEquals(1, Json.asInt(document.get("resultVersion")), "unexpected resultVersion");
        expectedCases = new ArrayList<>();
        for (Object entry : Json.asList(document.get("cases"))) {
            expectedCases.add(Json.asMap(entry));
        }
        assertTrue(!expectedCases.isEmpty(), "the differential corpus is empty");

        lossSets = new LinkedHashMap<>();
        Map<String, Object> corpus = Json.asMap(Json.parse(read(CORPUS)));
        for (Object entry : Json.asList(corpus.get("cases"))) {
            Map<String, Object> testCase = Json.asMap(entry);
            lossSets.put((String) testCase.get("name"), Json.asIntArray(testCase.get("missing")));
        }
    }

    /**
     * One dynamic test per corpus case. Each one builds the matrix, encodes, decodes the
     * recorded loss set, and compares every field the Python checker reports.
     */
    @TestFactory
    List<DynamicTest> differentialCorpus() {
        if (unavailableReason != null) {
            if (corpusIsRequired()) {
                return List.of(DynamicTest.dynamicTest("reference-corpus:required",
                        () -> fail("The independent reference corpus was required but could not"
                                + " run. " + unavailableReason)));
            }

            return List.of(DynamicTest.dynamicTest("reference-corpus:unavailable",
                    () -> abort(unavailableReason + requiredHint())));
        }

        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> expected : expectedCases) {
            String name = (String) expected.get("name");
            tests.add(DynamicTest.dynamicTest("corpus:" + name, () -> checkCase(name, expected)));
        }

        return tests;
    }

    private static void checkCase(String name, Map<String, Object> expected) {
        int k = Json.asInt(expected.get("k"));
        int r = Json.asInt(expected.get("r"));
        int n1 = Json.asInt(expected.get("n1"));
        int seed = Json.asInt(expected.get("seed"));
        int symbolLength = Json.asInt(expected.get("symbolLen"));
        long payloadSeed = ((Number) expected.get("payloadSeed")).longValue();
        int n = k + r;

        LdpcStaircase codec = LdpcStaircase.create(k, r, seed, n1);
        assertEquals(Json.asInt(expected.get("matrixEntryCount")),
                codec.parityCheckMatrix().entryCount(), name + ": matrix entry count");
        assertEquals(expected.get("matrixSha256"), sha256(codec.parityCheckMatrix().serialize()),
                name + ": canonical matrix serialization");

        byte[][] codeword = new byte[n][];
        for (int esi = 0; esi < k; esi++) {
            codeword[esi] = corpusSymbol(payloadSeed, esi, symbolLength);
        }
        for (int esi = k; esi < n; esi++) {
            codeword[esi] = new byte[symbolLength];
        }
        codec.encode(codeword);

        assertEquals(expected.get("sourceSha256"), sha256(concat(codeword, 0, k)),
                name + ": source symbols");
        assertEquals(expected.get("repairSha256"), sha256(concat(codeword, k, n)),
                name + ": repair symbols");

        boolean[] present = new boolean[n];
        Arrays.fill(present, true);
        for (int index : missingIndices(name)) {
            present[index] = false;
        }

        byte[][] symbols = new byte[n][];
        for (int i = 0; i < n; i++) {
            if (present[i]) {
                symbols[i] = codeword[i].clone();
            }
        }

        // decode() mutates present, marking every symbol it recovers, so the original
        // loss set is snapshotted first.
        boolean[] wasMissing = new boolean[n];
        for (int i = 0; i < n; i++) {
            wasMissing[i] = !present[i];
        }

        DecodeResult result = codec.decode(symbols, present);

        assertEquals(expected.get("complete"), result.isComplete(), name + ": completion flag");
        assertEquals(Json.asInt(expected.get("recoveredCount")), result.recoveredSymbolCount(),
                name + ": recovered symbol count");
        assertArrayEquals(Json.asIntArray(expected.get("missingSourceAfter")),
                result.missingSourceIndices(), name + ": source indices still missing");

        // The checker has no notion of stages, but "did peeling alone finish?" follows
        // from the mathematics, and the profile only reaches RESIDUAL_SOLVE when it did
        // not. Anything else is a real disagreement about the decode.
        boolean peelingSufficed = result.stage() != DecodeResult.Stage.RESIDUAL_SOLVE;
        assertEquals(expected.get("peelingSuffices"), peelingSufficed,
                name + ": whether degree-one peeling alone completed");

        List<Integer> recoveredSource = new ArrayList<>();
        List<Integer> recoveredRepair = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (wasMissing[i] && symbols[i] != null) {
                (i < k ? recoveredSource : recoveredRepair).add(i);
                assertArrayEquals(codeword[i], symbols[i],
                        name + ": recovered symbol " + i + " has the wrong bytes");
            }
        }
        assertArrayEquals(Json.asIntArray(expected.get("recoveredSource")), toIntArray(recoveredSource),
                name + ": recovered source indices");
        assertArrayEquals(Json.asIntArray(expected.get("recoveredRepair")), toIntArray(recoveredRepair),
                name + ": recovered repair indices");

        // Unrecovered source symbols contribute symbolLen zero bytes, so a decode that
        // silently left a hole cannot produce the same digest as one that filled it.
        byte[] recoveredSourceBytes = new byte[k * symbolLength];
        for (int i = 0; i < k; i++) {
            if (symbols[i] != null) {
                System.arraycopy(symbols[i], 0, recoveredSourceBytes, i * symbolLength, symbolLength);
            }
        }
        assertEquals(expected.get("recoveredSourceSha256"), sha256(recoveredSourceBytes),
                name + ": recovered source bytes");
    }

    /**
     * The corpus payload rule, documented in the specification repository's
     * {@code reference/README.md}: one independent SplitMix64 stream per symbol, keyed
     * by the case payload seed and the symbol's ESI, emitted as little-endian 64-bit
     * words and truncated to {@code S} bytes. Corpus test data only; not part of the
     * normative codec.
     */
    private static byte[] corpusSymbol(long payloadSeed, int esi, int symbolLength) {
        long state = payloadSeed ^ ((esi + 1L) * GOLDEN_GAMMA);
        byte[] out = new byte[symbolLength];
        int offset = 0;
        while (offset < symbolLength) {
            state += GOLDEN_GAMMA;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            z = z ^ (z >>> 31);
            for (int byteIndex = 0; byteIndex < 8 && offset < symbolLength; byteIndex++, offset++) {
                out[offset] = (byte) (z >>> (8 * byteIndex));
            }
        }

        return out;
    }

    /**
     * The results file records what was recovered, not what was lost, so the loss set
     * comes from the corpus definition the checker was given.
     */
    private static int[] missingIndices(String name) {
        int[] missing = lossSets.get(name);
        if (missing == null) {
            fail("corpus case " + name + " is missing from " + CORPUS);
        }

        return missing;
    }

    private static boolean corpusIsRequired() {
        String property = System.getProperty("ldpc.referenceCorpus.required");
        if (property != null) {
            return Boolean.parseBoolean(property);
        }
        String environment = System.getenv("LDPC_REFERENCE_CORPUS_REQUIRED");

        return environment != null && !environment.isEmpty() && !"0".equals(environment);
    }

    private static String findPython() {
        String configured = System.getProperty("ldpc.python", System.getenv("LDPC_PYTHON"));
        if (configured != null && !configured.isEmpty() && isSupportedPython(configured)) {
            return configured;
        }
        for (String candidate : new String[] {"python3", "python", "py"}) {
            if (isSupportedPython(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    /** Accepts an interpreter only if it really is Python 3.11 or newer. */
    private static boolean isSupportedPython(String executable) {
        try {
            Process process = new ProcessBuilder(executable, "-c",
                    "import sys; print(sys.version_info[0], sys.version_info[1])")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(readAll(process), StandardCharsets.UTF_8).trim();
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return false;
            }
            String[] parts = output.split("\\s+");
            return parts.length == 2 && Integer.parseInt(parts[0]) == 3
                    && Integer.parseInt(parts[1]) >= 11;
        } catch (IOException | InterruptedException | NumberFormatException e) {
            return false;
        }
    }

    private static int run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(readAll(process), StandardCharsets.UTF_8);
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("The reference checker did not finish within ten minutes");
        }
        if (process.exitValue() != 0) {
            System.out.println(output);
        }

        return process.exitValue();
    }

    private static byte[] readAll(Process process) throws IOException {
        return process.getInputStream().readAllBytes();
    }

    private static String requiredHint() {
        return " Set -Dldpc.referenceCorpus.required=true to make this a failure instead.";
    }

    private static String read(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] concat(byte[][] symbols, int from, int to) {
        int length = 0;
        for (int i = from; i < to; i++) {
            length += symbols[i].length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (int i = from; i < to; i++) {
            System.arraycopy(symbols[i], 0, out, offset, symbols[i].length);
            offset += symbols[i].length;
        }

        return out;
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }

        return out;
    }

    private static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                builder.append(String.format(Locale.ROOT, "%02x", value));
            }

            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }
}
