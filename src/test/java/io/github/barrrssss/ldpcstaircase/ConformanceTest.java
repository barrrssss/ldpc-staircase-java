package io.github.barrrssss.ldpcstaircase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The cross-language conformance gate. Parses {@code vectors/MANIFEST.json} and asserts
 * that this implementation reproduces every PRNG, matrix, encode, and decode vector
 * byte-for-byte.
 *
 * <p>Today it guards this implementation against accidental drift. Once
 * {@code ldpc-staircase-dotnet} exists, the same vectors move to
 * {@code ldpc-staircase-spec} and this becomes the byte-exactness contract between the
 * two runtimes — the role {@code ConformanceTest} already plays in
 * {@code erasure16-java}.
 *
 * <p>The vector {@code *.bin} files are raw bytes (marked {@code binary} in
 * {@code .gitattributes}); they are read verbatim, and every blob's SHA-256 is
 * rechecked so a corrupted checkout fails loudly here rather than skewing the
 * comparisons below.
 */
class ConformanceTest {

    private static final Path VECTORS_DIR = Paths.get("vectors").toAbsolutePath();

    private static Map<String, Object> manifest;

    @BeforeAll
    static void loadManifest() throws IOException {
        Path manifestPath = VECTORS_DIR.resolve("MANIFEST.json");
        if (!Files.exists(manifestPath)) {
            fail("Conformance vectors not found at " + manifestPath
                    + ". Run ./gradlew generateVectors to write them.");
        }

        String json = new String(Files.readAllBytes(manifestPath), StandardCharsets.UTF_8);
        Object parsed = Json.parse(json);
        assertTrue(parsed instanceof Map, "MANIFEST.json root must be a JSON object");
        manifest = Json.asMap(parsed);
    }

    @Test
    void manifestHasExpectedSections() {
        assertEquals(1, Json.asInt(manifest.get("manifestVersion")), "unexpected manifestVersion");
        for (String section : new String[] {"prngRaw", "prngBounded", "matrix", "encode", "decode",
                "files"}) {
            assertTrue(manifest.get(section) instanceof List, section + " section missing");
            assertTrue(!Json.asList(manifest.get(section)).isEmpty(), section + " section is empty");
        }
    }

    /**
     * Every blob referenced in the manifest must have the listed byte length and
     * SHA-256. This anchors the vectors as the byte-exact oracle.
     */
    @TestFactory
    List<DynamicTest> fileDigests() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Object entry : Json.asList(manifest.get("files"))) {
            Map<String, Object> file = Json.asMap(entry);
            String path = (String) file.get("path");
            int length = Json.asInt(file.get("length"));
            String expectedSha = (String) file.get("sha256");
            tests.add(DynamicTest.dynamicTest("file:" + path, () -> {
                byte[] bytes = readVector(path);
                assertEquals(length, bytes.length, "byte length mismatch for " + path);
                assertEquals(expectedSha, sha256Hex(bytes), "SHA-256 mismatch for " + path);
            }));
        }

        return tests;
    }

    /**
     * PRNG vectors: the raw Park-Miller stream at listed ordinals (SPEC.md section 3).
     * A divergence here means every downstream matrix is wrong.
     */
    @TestFactory
    List<DynamicTest> prngRawVectors() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Object entry : Json.asList(manifest.get("prngRaw"))) {
            Map<String, Object> testCase = Json.asMap(entry);
            String name = (String) testCase.get("name");
            tests.add(DynamicTest.dynamicTest("prng-raw:" + name, () -> {
                PmmsRandom rng = new PmmsRandom(Json.asInt(testCase.get("seed")));
                int position = 0;
                int value = 0;
                for (Object valueEntry : Json.asList(testCase.get("values"))) {
                    Map<String, Object> expected = Json.asMap(valueEntry);
                    int ordinal = Json.asInt(expected.get("ordinal"));
                    while (position < ordinal) {
                        value = rng.next();
                        position++;
                    }

                    assertEquals(Json.asInt(expected.get("value")), value,
                            name + ": value " + ordinal + " must match the manifest");
                }
            }));
        }

        return tests;
    }

    /** PRNG vectors: the bounded scaling of SPEC.md section 3. */
    @TestFactory
    List<DynamicTest> prngBoundedVectors() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Object entry : Json.asList(manifest.get("prngBounded"))) {
            Map<String, Object> testCase = Json.asMap(entry);
            String name = (String) testCase.get("name");
            tests.add(DynamicTest.dynamicTest("prng-bounded:" + name, () -> {
                PmmsRandom rng = new PmmsRandom(Json.asInt(testCase.get("seed")));
                int maxv = Json.asInt(testCase.get("maxv"));
                int[] expected = Json.asIntArray(testCase.get("draws"));
                int[] actual = new int[expected.length];
                for (int i = 0; i < expected.length; i++) {
                    actual[i] = rng.nextBounded(maxv);
                }

                assertArrayEquals(expected, actual, name + ": bounded draws must match the manifest");
            }));
        }

        return tests;
    }

    /**
     * Matrix vectors: rebuilding {@code H} from {@code (K, R, N1, seed)} must reproduce
     * the committed canonical serialization exactly (SPEC.md sections 4 and 7.1).
     */
    @TestFactory
    List<DynamicTest> matrixVectors() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Object entry : Json.asList(manifest.get("matrix"))) {
            Map<String, Object> testCase = Json.asMap(entry);
            String name = (String) testCase.get("name");
            tests.add(DynamicTest.dynamicTest("matrix:" + name, () -> {
                ParityCheckMatrix h = ParityCheckMatrix.build(
                        Json.asInt(testCase.get("k")),
                        Json.asInt(testCase.get("r")),
                        Json.asInt(testCase.get("n1")),
                        Json.asInt(testCase.get("seed")));

                assertEquals(Json.asInt(testCase.get("entryCount")), h.entryCount(),
                        name + ": entry count");
                assertBytesEqual(name, readVector((String) testCase.get("file")), h.serialize());
            }));
        }

        return tests;
    }

    /**
     * Encode vectors: load the {@code K} source symbols, encode, and assert the produced
     * repair blob equals the committed one byte-for-byte (SPEC.md section 5).
     */
    @TestFactory
    List<DynamicTest> encodeVectors() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Object entry : Json.asList(manifest.get("encode"))) {
            Map<String, Object> testCase = Json.asMap(entry);
            String name = (String) testCase.get("name");
            tests.add(DynamicTest.dynamicTest("encode:" + name, () -> {
                EncodeCase encodeCase = new EncodeCase(testCase);
                byte[][] symbols = encodeCase.sourceOnlySymbols();
                encodeCase.codec.encode(symbols);

                for (int p = 0; p < encodeCase.r; p++) {
                    assertBytesEqual(name + " repair[" + p + "]", encodeCase.symbol(encodeCase.k + p),
                            symbols[encodeCase.k + p]);
                }

                assertTrue(encodeCase.codec.verify(symbols),
                        name + ": verify() must hold after encode");
            }));
        }

        return tests;
    }

    /**
     * Decode vectors: start from the recorded present subset, decode, and assert the
     * completion flag, the stage, the recovered count, the still-missing source indices,
     * and the bytes of every symbol the decode claims to hold (SPEC.md section 6).
     */
    @TestFactory
    List<DynamicTest> decodeVectors() {
        Map<String, Map<String, Object>> encodeCases = new HashMap<>();
        for (Object entry : Json.asList(manifest.get("encode"))) {
            Map<String, Object> testCase = Json.asMap(entry);
            encodeCases.put((String) testCase.get("name"), testCase);
        }

        List<DynamicTest> tests = new ArrayList<>();
        for (Object entry : Json.asList(manifest.get("decode"))) {
            Map<String, Object> testCase = Json.asMap(entry);
            String name = (String) testCase.get("name");
            tests.add(DynamicTest.dynamicTest("decode:" + name, () -> {
                Map<String, Object> encodeCase = encodeCases.get((String) testCase.get("encodeCase"));
                assertTrue(encodeCase != null, name + ": unknown encodeCase reference");
                EncodeCase source = new EncodeCase(encodeCase);

                boolean[] present = new boolean[source.n];
                Arrays.fill(present, true);
                for (int index : Json.asIntArray(testCase.get("missing"))) {
                    present[index] = false;
                }

                byte[][] symbols = new byte[source.n][];
                for (int i = 0; i < source.n; i++) {
                    if (present[i]) {
                        symbols[i] = source.symbol(i);
                    }
                }

                DecodeResult result = source.codec.decode(symbols, present);

                assertEquals(testCase.get("expectComplete"), result.isComplete(),
                        name + ": completion flag");
                assertEquals(testCase.get("expectStage"), result.stage().name(), name + ": stage");
                assertEquals(Json.asInt(testCase.get("expectRecoveredSymbols")),
                        result.recoveredSymbolCount(), name + ": recovered symbol count");
                assertArrayEquals(Json.asIntArray(testCase.get("expectMissingSource")),
                        result.missingSourceIndices(), name + ": still-missing source indices");

                // Whatever the decode claims to hold must be the original bytes, whether
                // it was received, peeled, or solved.
                for (int i = 0; i < source.n; i++) {
                    if (present[i]) {
                        assertBytesEqual(name + " symbol[" + i + "]", source.symbol(i), symbols[i]);
                    }
                }
            }));
        }

        return tests;
    }

    /** One encode case's parameters and committed symbol blobs. */
    private static final class EncodeCase {
        final int k;
        final int r;
        final int n;
        final int symbolLength;
        final LdpcStaircase codec;
        private final byte[] sourceBlob;
        private final byte[] repairBlob;

        EncodeCase(Map<String, Object> testCase) {
            this.k = Json.asInt(testCase.get("k"));
            this.r = Json.asInt(testCase.get("r"));
            this.n = k + r;
            this.symbolLength = Json.asInt(testCase.get("symbolLen"));
            this.codec = LdpcStaircase.create(k, r, Json.asInt(testCase.get("seed")),
                    Json.asInt(testCase.get("n1")));
            this.sourceBlob = readVector((String) testCase.get("sourceBlob"));
            this.repairBlob = readVector((String) testCase.get("repairBlob"));

            assertEquals(k * symbolLength, sourceBlob.length, "source blob length");
            assertEquals(r * symbolLength, repairBlob.length, "repair blob length");
        }

        /** Symbol {@code index} sliced out of the committed blobs. */
        byte[] symbol(int index) {
            byte[] blob = index < k ? sourceBlob : repairBlob;
            int offset = (index < k ? index : index - k) * symbolLength;
            return Arrays.copyOfRange(blob, offset, offset + symbolLength);
        }

        /** An encode input: the {@code K} source symbols with empty repair slots. */
        byte[][] sourceOnlySymbols() {
            byte[][] symbols = new byte[n][];
            for (int i = 0; i < k; i++) {
                symbols[i] = symbol(i);
            }

            return symbols;
        }
    }

    private static byte[] readVector(String name) {
        try {
            return Files.readAllBytes(VECTORS_DIR.resolve(name));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read vector blob: " + name, e);
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }

            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    /**
     * Byte-exact comparison with a precise diagnostic on the first differing offset — a
     * vector mismatch indicates a real cross-language divergence, so the exact byte
     * values are surfaced.
     */
    private static void assertBytesEqual(String label, byte[] expected, byte[] actual) {
        if (expected.length != actual.length) {
            fail(label + ": length mismatch - expected " + expected.length + " bytes, got "
                    + actual.length);
        }

        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                fail(label + ": byte mismatch at offset " + i
                        + " - expected 0x" + String.format("%02x", expected[i] & 0xFF)
                        + ", got 0x" + String.format("%02x", actual[i] & 0xFF));
            }
        }
    }
}
