package io.github.barrrssss.ldpcstaircase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Writes the conformance vectors into the {@code ldpc-staircase-spec} submodule: the
 * raw {@code *.bin} blobs and the {@code MANIFEST.json} that indexes them. Run through
 * {@code ./gradlew generateVectors}.
 *
 * <p>This repository is the vectors' single generator; {@code ldpc-staircase-dotnet}
 * only verifies them. They are the cross-language oracle, so regenerating them is a
 * <b>specification change</b>, not a routine step — a diff here means the .NET
 * implementation must change to match. Run it only when SPEC.md itself changes,
 * review the diff, and commit it in {@code ldpc-staircase-spec}.
 *
 * <p>Everything here is deterministic: symbol payloads come from {@link SplitMix64} at
 * fixed seeds, and the decode cases are found by a fixed-seed search over loss
 * patterns, so a rerun on any machine reproduces the same bytes.
 */
public final class VectorGenerator {

    private static final int DECODE_K = 120;
    private static final int DECODE_R = 60;
    private static final int DECODE_N1 = 7;
    private static final int DECODE_SEED = 31337;
    private static final int DECODE_SYMBOL_LENGTH = 24;
    private static final String DECODE_CASE = "enc-k120-r60-n1_7-s31337-len24";

    // A production-scale shape at the top of the profile's calibrated envelope. Every
    // case above is small enough to reason about by hand, which is what makes them good
    // at pinning the algorithms; none of them reaches the scale a consumer actually
    // ships, where the matrix build draws six figures of PRNG values and the residual
    // solve is thousands of columns wide. This one does.
    //
    // The symbol length is deliberately short. Symbols are combined by XOR, so symbol
    // length is independent of everything the large shape exercises, and a short one
    // keeps the committed blobs to a few hundred kilobytes.
    private static final int LARGE_K = 16000;
    private static final int LARGE_R = 8192;
    private static final int LARGE_N1 = 7;
    private static final int LARGE_SEED = 8191;
    private static final int LARGE_SYMBOL_LENGTH = 8;
    private static final String LARGE_CASE = "enc-k16000-r8192-n1_7-s8191-len8";

    /**
     * Missing-index lists longer than this are written as a blob instead of being
     * inlined. The large decode cases lose thousands of symbols; inlining those lists
     * would make the manifest mostly integers and unreviewable. Every existing case is
     * far below the threshold, so their manifest text is unchanged.
     */
    private static final int INLINE_MISSING_MAX = 256;

    private final Path directory;
    private final List<String[]> files = new ArrayList<>(); // path, length, sha256

    private VectorGenerator(Path directory) {
        this.directory = directory;
    }

    public static void main(String[] args) throws IOException {
        Path directory = Paths.get(args.length > 0 ? args[0] : "vectors").toAbsolutePath();
        Files.createDirectories(directory);
        new VectorGenerator(directory).generate();
        System.out.println("Wrote conformance vectors to " + directory);
    }

    private void generate() throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"manifestVersion\": 2,\n");
        json.append("  \"description\": ").append(quote(
                        "ldpc-staircase conformance vectors for the constrained RFC 5170 "
                                + "LDPC-Staircase profile. Symbol payloads are produced by a documented "
                                + "SplitMix64 PRNG; see VectorGenerator. Every implementation must "
                                + "reproduce these byte-for-byte."))
                .append(",\n");

        json.append("  \"prngRaw\": [\n");
        json.append(prngRaw("raw-seed-1", 1, new int[] {1, 2, 3, 10, 100, 10000})).append(",\n");
        json.append(prngRaw("raw-seed-12345", 12345, new int[] {1, 2, 3, 4, 5})).append(",\n");
        json.append(prngRaw("raw-seed-max", PmmsRandom.MAX_SEED, new int[] {1, 2, 3})).append("\n");
        json.append("  ],\n");

        json.append("  \"prngBounded\": [\n");
        json.append(prngBounded("bounded-seed-1-max100", 1, 100, 16)).append(",\n");
        json.append(prngBounded("bounded-seed-42-max65537", 42, 65537, 8)).append(",\n");
        json.append(prngBounded("bounded-seed-987654321-max3", 987654321, 3, 12)).append("\n");
        json.append("  ],\n");

        json.append("  \"matrix\": [\n");
        List<String> matrices = new ArrayList<>();
        matrices.add(matrixCase("mtx-k16-r8-n1_3-s1", 16, 8, 3, 1));
        matrices.add(matrixCase("mtx-k64-r32-n1_7-s42", 64, 32, 7, 42));
        matrices.add(matrixCase("mtx-k10-r5-n1_3-smax", 10, 5, 3, PmmsRandom.MAX_SEED));
        matrices.add(matrixCase("mtx-k1-r1-n1_1-s7", 1, 1, 1, 7));
        matrices.add(matrixCase("mtx-k4-r0-n1_1-s1", 4, 0, 1, 1));
        matrices.add(matrixCase("mtx-k250-r125-n1_7-s123456", 250, 125, 7, 123456));
        // SPEC.md section 4.3 coverage. Every case above leaves the low-degree-raising
        // pass with nothing to do: each has exactly N1*K + (2R - 1) entries, so the pass
        // adds none. These three put N1*K at or below R so rows really do come out of
        // section 4.2 below degree two, and the pass has to raise them.
        matrices.add(matrixCase("mtx-k2-r8-n1_1-s1", 2, 8, 1, 1));
        matrices.add(matrixCase("mtx-k8-r16-n1_2-s3", 8, 16, 2, 3));
        matrices.add(matrixCase("mtx-k3-r6-n1_1-smax", 3, 6, 1, PmmsRandom.MAX_SEED));
        // Production scale: 128,383 entries over 8,192 rows, built from 112,000 bounded
        // PRNG draws. A serialization that agrees here agrees at any shape a consumer
        // will ask for.
        matrices.add(matrixCase("mtx-k16000-r8192-n1_7-s8191", LARGE_K, LARGE_R, LARGE_N1, LARGE_SEED));
        json.append(String.join(",\n", matrices)).append("\n");
        json.append("  ],\n");

        json.append("  \"encode\": [\n");
        List<String> encodes = new ArrayList<>();
        encodes.add(encodeCase("enc-k16-r8-n1_3-s1-len32", 16, 8, 3, 1, 32, 1001));
        encodes.add(encodeCase("enc-k64-r32-n1_7-s42-len17", 64, 32, 7, 42, 17, 1002));
        encodes.add(encodeCase("enc-k1-r1-n1_1-s7-len8", 1, 1, 1, 7, 8, 1003));
        encodes.add(encodeCase("enc-k4-r0-n1_1-s1-len16", 4, 0, 1, 1, 16, 1004));
        encodes.add(encodeCase(DECODE_CASE, DECODE_K, DECODE_R, DECODE_N1, DECODE_SEED,
                DECODE_SYMBOL_LENGTH, 1005));
        // Repair bytes over a matrix that section 4.3 actually modified, at an odd
        // symbol length.
        encodes.add(encodeCase("enc-k2-r8-n1_1-s1-len5", 2, 8, 1, 1, 5, 1006));
        // Production scale: 16,000 source and 8,192 repair symbols over the matrix above.
        encodes.add(encodeCase(LARGE_CASE, LARGE_K, LARGE_R, LARGE_N1, LARGE_SEED,
                LARGE_SYMBOL_LENGTH, 1007));
        json.append(String.join(",\n", encodes)).append("\n");
        json.append("  ],\n");

        json.append("  \"decode\": [\n");
        json.append(String.join(",\n", decodeCases())).append("\n");
        json.append("  ],\n");

        json.append("  \"files\": [\n");
        files.sort((a, b) -> a[0].compareTo(b[0]));
        List<String> fileEntries = new ArrayList<>();
        for (String[] file : files) {
            fileEntries.add("    {\"path\": " + quote(file[0]) + ", \"length\": " + file[1]
                    + ", \"sha256\": " + quote(file[2]) + "}");
        }
        json.append(String.join(",\n", fileEntries)).append("\n");
        json.append("  ]\n");
        json.append("}\n");

        Files.write(directory.resolve("MANIFEST.json"), json.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // Cases
    // ------------------------------------------------------------------

    private static String prngRaw(String name, int seed, int[] ordinals) {
        PmmsRandom rng = new PmmsRandom(seed);
        List<String> values = new ArrayList<>();
        int position = 0;
        for (int ordinal : ordinals) {
            int value = 0;
            while (position < ordinal) {
                value = rng.next();
                position++;
            }

            values.add("{\"ordinal\": " + ordinal + ", \"value\": " + value + "}");
        }

        return "    {\"name\": " + quote(name) + ", \"seed\": " + seed + ", \"values\": ["
                + String.join(", ", values) + "]}";
    }

    private static String prngBounded(String name, int seed, int maxv, int count) {
        PmmsRandom rng = new PmmsRandom(seed);
        List<String> draws = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            draws.add(Integer.toString(rng.nextBounded(maxv)));
        }

        return "    {\"name\": " + quote(name) + ", \"seed\": " + seed + ", \"maxv\": " + maxv
                + ", \"draws\": [" + String.join(", ", draws) + "]}";
    }

    private String matrixCase(String name, int k, int r, int n1, int seed) throws IOException {
        ParityCheckMatrix h = ParityCheckMatrix.build(k, r, n1, seed);
        String file = name + ".bin";
        write(file, h.serialize());

        return "    {\"name\": " + quote(name) + ", \"k\": " + k + ", \"r\": " + r + ", \"n1\": " + n1
                + ", \"seed\": " + seed + ", \"entryCount\": " + h.entryCount()
                + ", \"file\": " + quote(file) + "}";
    }

    private String encodeCase(String name, int k, int r, int n1, int seed, int symbolLength,
            long dataSeed) throws IOException {
        byte[][] symbols = encodedSymbols(k, r, n1, seed, symbolLength, dataSeed);

        // One blob per part rather than one file per symbol: at K = 120 the per-symbol
        // layout would be hundreds of tiny files, and the symbol length is fixed anyway.
        String sourceBlob = name + "-src.bin";
        write(sourceBlob, concat(symbols, 0, k, symbolLength));

        String repairBlob = name + "-rep.bin";
        write(repairBlob, concat(symbols, k, r, symbolLength));

        return "    {\"name\": " + quote(name) + ", \"k\": " + k + ", \"r\": " + r + ", \"n1\": " + n1
                + ", \"seed\": " + seed + ", \"symbolLen\": " + symbolLength
                + ", \"sourceBlob\": " + quote(sourceBlob)
                + ", \"repairBlob\": " + quote(repairBlob) + "}";
    }

    /** Concatenates {@code count} equal-length symbols starting at {@code from}. */
    private static byte[] concat(byte[][] symbols, int from, int count, int symbolLength) {
        byte[] blob = new byte[count * symbolLength];
        for (int i = 0; i < count; i++) {
            System.arraycopy(symbols[from + i], 0, blob, i * symbolLength, symbolLength);
        }

        return blob;
    }

    /**
     * Builds the decode cases. Three are fixed by construction; the peeling-only,
     * residual-solve, and expected-failure cases are found by a deterministic search
     * over loss patterns so that the committed vectors genuinely exercise all three
     * outcomes of SPEC.md section 6.
     */
    private List<String> decodeCases() throws IOException {
        int n = DECODE_K + DECODE_R;
        byte[][] encoded = encodedSymbols(DECODE_K, DECODE_R, DECODE_N1, DECODE_SEED,
                DECODE_SYMBOL_LENGTH, 1005);
        LdpcStaircase codec = LdpcStaircase.create(DECODE_K, DECODE_R, DECODE_SEED, DECODE_N1);

        List<String> cases = new ArrayList<>();
        cases.add(decodeCase("dec-nothing-missing", DECODE_CASE, codec, encoded, new int[0]));

        int[] repairOnly = new int[DECODE_R];
        for (int p = 0; p < DECODE_R; p++) {
            repairOnly[p] = DECODE_K + p;
        }
        cases.add(decodeCase("dec-all-repair-missing", DECODE_CASE, codec, encoded, repairOnly));

        // Half the repair budget spent on source symbols: comfortably inside the code's
        // capability and expected to peel cleanly.
        int[] easy = new int[DECODE_R / 2];
        for (int i = 0; i < easy.length; i++) {
            easy[i] = i * 4;
        }
        cases.add(decodeCase("dec-source-band", DECODE_CASE, codec, encoded, easy));

        // Peeling is searched at half the repair budget: at the full budget the graph is
        // close enough to its threshold that peeling essentially always stalls, which is
        // itself the reason the residual solve is mandatory.
        addSearched(cases, "dec-random-loss-peeling", codec, encoded, n, Outcome.PEELING, DECODE_R / 2);
        addSearched(cases, "dec-max-loss-residual", codec, encoded, n, Outcome.RESIDUAL, DECODE_R);
        addSearched(cases, "dec-max-loss-failure", codec, encoded, n, Outcome.FAILURE, DECODE_R);

        cases.addAll(largeDecodeCases());

        return cases;
    }

    /**
     * Builds the production-scale decode cases over {@link #LARGE_CASE}.
     *
     * <p>Unlike the small cases these are not searched. A search replays a full decode
     * per attempt, and at this shape a single residual solve is three orders of magnitude
     * more work than one at {@code K = 120}; twenty thousand of them is not a thing a
     * generator can do. The loss patterns are instead drawn from fixed SplitMix64 seeds,
     * which is equally reproducible, and the stage each case must reach is asserted so a
     * case cannot quietly stop covering what its name says it covers.
     */
    private List<String> largeDecodeCases() throws IOException {
        int n = LARGE_K + LARGE_R;
        byte[][] encoded = encodedSymbols(LARGE_K, LARGE_R, LARGE_N1, LARGE_SEED,
                LARGE_SYMBOL_LENGTH, 1007);
        LdpcStaircase codec = LdpcStaircase.create(LARGE_K, LARGE_R, LARGE_SEED, LARGE_N1);

        List<String> cases = new ArrayList<>();

        // Half the repair budget, comfortably inside the code's capability: peeling alone
        // carries it, over a graph with 24,192 nodes.
        cases.add(decodeCase("dec-large-peeling", LARGE_CASE, codec, encoded,
                deterministicLoss(n, LARGE_R / 2, 0x1A46E1000L), DecodeResult.Stage.PEELING));

        // Three quarters of it: peeling stalls and the residual solve finishes the
        // recovery. This is the case no small vector can stand in for — the solve here is
        // a system of thousands of unknowns, where an implementation that gets the
        // elimination order or the pivot rule subtly wrong still passes at K = 120.
        cases.add(decodeCase("dec-large-residual", LARGE_CASE, codec, encoded,
                deterministicLoss(n, LARGE_R * 3 / 4, 0x1A46E1800L),
                DecodeResult.Stage.RESIDUAL_SOLVE));

        return cases;
    }

    /**
     * Draws {@code lossCount} distinct indices below {@code n} from a fixed seed and
     * returns them ascending.
     */
    private static int[] deterministicLoss(int n, int lossCount, long seed) {
        SplitMix64 rng = new SplitMix64(seed);
        boolean[] chosen = new boolean[n];
        int drawn = 0;
        while (drawn < lossCount) {
            int index = rng.nextInt(n);
            if (!chosen[index]) {
                chosen[index] = true;
                drawn++;
            }
        }

        int[] missing = new int[lossCount];
        int at = 0;
        for (int i = 0; i < n; i++) {
            if (chosen[i]) {
                missing[at++] = i;
            }
        }

        return missing;
    }

    private void addSearched(List<String> cases, String name, LdpcStaircase codec, byte[][] encoded,
            int n, Outcome outcome, int lossCount) throws IOException {
        int[] missing = search(codec, encoded, n, outcome, lossCount);
        if (missing == null) {
            System.out.println("WARNING: no " + outcome + " case found at " + lossCount + " losses");
            return;
        }

        cases.add(decodeCase(name, DECODE_CASE, codec, encoded, missing));
    }

    private enum Outcome { PEELING, RESIDUAL, FAILURE }

    /**
     * Finds the first loss pattern of exactly {@code lossCount} symbols producing the
     * requested outcome. At {@code lossCount = R} the pattern spends the whole repair
     * budget — the loss an MDS code would absorb exactly — which is where the non-MDS
     * margin shows up. The search PRNG is seeded per outcome so each case is stable and
     * independent of the others.
     */
    private static int[] search(LdpcStaircase codec, byte[][] encoded, int n, Outcome target,
            int lossCount) {
        SplitMix64 rng = new SplitMix64(0x51DE0000L + target.ordinal());

        for (int attempt = 0; attempt < 20_000; attempt++) {
            boolean[] present = new boolean[n];
            Arrays.fill(present, true);
            int removed = 0;
            while (removed < lossCount) {
                int index = rng.nextInt(n);
                if (present[index]) {
                    present[index] = false;
                    removed++;
                }
            }

            int[] missing = missingIndices(present);
            DecodeResult peeled = replay(codec, encoded, present.clone(),
                    DecodeOptions.defaults().withResidualSolve(false));
            DecodeResult full = replay(codec, encoded, present.clone(), DecodeOptions.defaults());

            switch (target) {
                case PEELING:
                    if (peeled.isComplete()) {
                        return missing;
                    }
                    break;
                case RESIDUAL:
                    if (!peeled.isComplete() && full.isComplete()) {
                        return missing;
                    }
                    break;
                case FAILURE:
                    if (!full.isComplete()) {
                        return missing;
                    }
                    break;
                default:
                    throw new IllegalStateException();
            }
        }

        return null;
    }

    private String decodeCase(String name, String encodeCase, LdpcStaircase codec, byte[][] encoded,
            int[] missing) throws IOException {
        return decodeCase(name, encodeCase, codec, encoded, missing, null);
    }

    /**
     * Emits one decode case. When {@code requiredStage} is non-null the decode must have
     * reached exactly that stage: the large cases are named for the stage they exist to
     * cover, and a drift to a different one would leave that stage uncovered under a name
     * claiming otherwise.
     */
    private String decodeCase(String name, String encodeCase, LdpcStaircase codec, byte[][] encoded,
            int[] missing, DecodeResult.Stage requiredStage) throws IOException {
        int n = codec.encodingSymbolCount();
        boolean[] present = new boolean[n];
        Arrays.fill(present, true);
        for (int index : missing) {
            present[index] = false;
        }

        DecodeResult result = replay(codec, encoded, present, DecodeOptions.defaults());
        if (requiredStage != null && result.stage() != requiredStage) {
            throw new IllegalStateException(name + ": expected the decode to reach "
                    + requiredStage + " but it reached " + result.stage());
        }

        List<String> stillMissing = new ArrayList<>();
        for (int index : result.missingSourceIndices()) {
            stillMissing.add(Integer.toString(index));
        }

        return "    {\"name\": " + quote(name) + ", \"encodeCase\": " + quote(encodeCase)
                + ", \"expectComplete\": " + result.isComplete()
                + ", \"expectStage\": " + quote(result.stage().name())
                + ", \"expectRecoveredSymbols\": " + result.recoveredSymbolCount()
                + ",\n     " + missingField(name, missing) + ","
                + "\n     \"expectMissingSource\": [" + String.join(", ", stillMissing) + "]}";
    }

    /**
     * Renders a case's missing-index list: inline for the small cases, and as a
     * {@code missingBlob} reference past {@link #INLINE_MISSING_MAX} entries.
     */
    private String missingField(String caseName, int[] missing) throws IOException {
        if (missing.length <= INLINE_MISSING_MAX) {
            List<String> missingText = new ArrayList<>();
            for (int index : missing) {
                missingText.add(Integer.toString(index));
            }

            return "\"missing\": [" + String.join(", ", missingText) + "]";
        }

        // Big-endian u32 indices, ascending - SPEC.md section 7.2.
        byte[] blob = new byte[missing.length * 4];
        for (int i = 0; i < missing.length; i++) {
            int value = missing[i];
            blob[i * 4] = (byte) (value >>> 24);
            blob[i * 4 + 1] = (byte) (value >>> 16);
            blob[i * 4 + 2] = (byte) (value >>> 8);
            blob[i * 4 + 3] = (byte) value;
        }

        String file = caseName + "-missing.bin";
        write(file, blob);
        return "\"missingBlob\": " + quote(file);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static byte[][] encodedSymbols(int k, int r, int n1, int seed, int symbolLength,
            long dataSeed) {
        LdpcStaircase codec = LdpcStaircase.create(k, r, seed, n1);
        byte[][] symbols = new byte[k + r][];
        SplitMix64 rng = new SplitMix64(dataSeed);
        for (int i = 0; i < k; i++) {
            symbols[i] = rng.nextBytes(symbolLength);
        }

        codec.encode(symbols);
        return symbols;
    }

    private static DecodeResult replay(LdpcStaircase codec, byte[][] encoded, boolean[] present,
            DecodeOptions options) {
        byte[][] symbols = new byte[encoded.length][];
        for (int i = 0; i < encoded.length; i++) {
            if (present[i]) {
                symbols[i] = encoded[i].clone();
            }
        }

        return codec.decode(symbols, present, options);
    }

    private static int[] missingIndices(boolean[] present) {
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < present.length; i++) {
            if (!present[i]) {
                missing.add(i);
            }
        }

        int[] result = new int[missing.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = missing.get(i);
        }

        return result;
    }

    private void write(String name, byte[] data) throws IOException {
        Files.write(directory.resolve(name), data);
        files.add(new String[] {name, Integer.toString(data.length), sha256Hex(data)});
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
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"' || ch == '\\') {
                sb.append('\\').append(ch);
            } else {
                sb.append(ch);
            }
        }

        return sb.append('"').toString();
    }
}
