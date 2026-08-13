package io.github.barrrssss.ldpcstaircase;

import java.util.Arrays;

/**
 * Systematic, fixed-rate LDPC-Staircase erasure coder over GF(2), following the
 * RFC 5170 section 6 core constrained to the profile in SPEC.md. Given {@code K}
 * equal-length source symbols it produces {@code R} repair symbols by sparse XOR;
 * a decoder can usually reconstruct the source from {@code K + D} received symbols.
 *
 * <p>Unlike an MDS code such as Reed-Solomon there is <b>no "any {@code K} of
 * {@code N}" guarantee</b>. {@code D} is a margin that depends on the graph, the seed,
 * and the loss pattern, and must be measured for the parameters in use. Always read
 * completion from {@link DecodeResult#isComplete()}.
 *
 * <p>The coder is deliberately abstract: symbol byte arrays in, symbol byte arrays
 * out. It performs no I/O, compression, encryption, or page layout. Symbols may be
 * any positive length; the code operates byte-wise over GF(2).
 *
 * <p>Instances are immutable and safe to share between threads; {@link #encode} and
 * {@link #decode} operate only on the caller's arrays.
 */
public final class LdpcStaircase {

    /** The default profile value for {@code N1}. */
    public static final int DEFAULT_LEFT_DEGREE = ParityCheckMatrix.DEFAULT_LEFT_DEGREE;

    private static final int[] NO_INDICES = new int[0];

    private final ParityCheckMatrix matrix;
    private final int sourceSymbolCount;
    private final int repairSymbolCount;
    private final int encodingSymbolCount;

    private LdpcStaircase(ParityCheckMatrix matrix) {
        this.matrix = matrix;
        this.sourceSymbolCount = matrix.sourceSymbolCount();
        this.repairSymbolCount = matrix.repairSymbolCount();
        this.encodingSymbolCount = matrix.encodingSymbolCount();
    }

    /**
     * Creates a coder for {@code K} source and {@code R} repair symbols at the default
     * {@code N1 = 7}, building the parity check matrix from {@code seed}.
     *
     * @throws LdpcStaircaseException if any parameter is out of range.
     */
    public static LdpcStaircase create(int sourceSymbolCount, int repairSymbolCount, int seed) {
        return create(sourceSymbolCount, repairSymbolCount, seed, DEFAULT_LEFT_DEGREE);
    }

    /**
     * Creates a coder for {@code K} source and {@code R} repair symbols with an explicit
     * {@code N1}, building the parity check matrix from {@code seed}. Requires
     * {@code K >= 1}, {@code R >= 0}, {@code 1 <= N1 <= R} when {@code R >= 1}, and
     * {@code seed} in {@code [1, 2147483646]}.
     *
     * @throws LdpcStaircaseException if any parameter is out of range.
     */
    public static LdpcStaircase create(int sourceSymbolCount, int repairSymbolCount, int seed,
            int leftDegree) {
        return new LdpcStaircase(
                ParityCheckMatrix.build(sourceSymbolCount, repairSymbolCount, leftDegree, seed));
    }

    /**
     * Wraps an already-built matrix. Matrix construction is the only per-code setup
     * cost, so a caller that encodes and later decodes the same code can build it once.
     */
    public static LdpcStaircase of(ParityCheckMatrix matrix) {
        if (matrix == null) {
            throw new LdpcStaircaseException("matrix must not be null.");
        }

        return new LdpcStaircase(matrix);
    }

    /** Number of source symbols, {@code K}. */
    public int sourceSymbolCount() {
        return sourceSymbolCount;
    }

    /** Number of repair symbols, {@code R}. */
    public int repairSymbolCount() {
        return repairSymbolCount;
    }

    /** Number of encoding symbols, {@code N = K + R}. */
    public int encodingSymbolCount() {
        return encodingSymbolCount;
    }

    /** The configured number of ones per source column, {@code N1}. */
    public int leftDegree() {
        return matrix.leftDegree();
    }

    /** The PRNG seed this code's matrix was built from. */
    public int seed() {
        return matrix.seed();
    }

    /** The parity check matrix, exposed for analysis and loss simulation. */
    public ParityCheckMatrix parityCheckMatrix() {
        return matrix;
    }

    /**
     * Encodes in place. {@code symbols} must have {@code K + R} entries; slots
     * {@code [0..K)} hold the caller-supplied source symbols, of equal positive length,
     * and slots {@code [K..K+R)} are overwritten with the computed repair symbols
     * (allocated if null or of the wrong length). With {@code R == 0} this only
     * validates the source symbols.
     *
     * <p>Repair symbols are produced in ascending order because each one is the running
     * XOR of its equation's source symbols with its predecessor (SPEC.md section 5).
     *
     * @throws LdpcStaircaseException if the symbol count or lengths are invalid.
     */
    public void encode(byte[][] symbols) {
        int symbolLength = validateSourceSymbols(symbols);

        byte[] previous = null;
        for (int p = 0; p < repairSymbolCount; p++) {
            byte[] repair = ensureSymbol(symbols, sourceSymbolCount + p, symbolLength);
            Arrays.fill(repair, (byte) 0);

            int from = matrix.rowOffsets[p];
            int to = matrix.rowOffsets[p + 1];
            for (int idx = from; idx < to; idx++) {
                int c = matrix.rowEntries[idx];
                if (c < sourceSymbolCount) {
                    Symbols.xorInto(repair, symbols[c]);
                }
            }

            if (previous != null) {
                Symbols.xorInto(repair, previous);
            }

            previous = repair;
        }
    }

    /**
     * Returns {@code true} if all {@code K + R} symbols satisfy every parity equation —
     * i.e. each row of the parity check matrix XORs its symbols to zero. Expects a
     * fully populated symbol array of equal positive lengths.
     *
     * @throws LdpcStaircaseException if the symbol array is structurally invalid.
     */
    public boolean verify(byte[][] symbols) {
        int symbolLength = validateAllSymbols(symbols);

        byte[] accumulator = new byte[symbolLength];
        for (int i = 0; i < repairSymbolCount; i++) {
            Arrays.fill(accumulator, (byte) 0);
            int from = matrix.rowOffsets[i];
            int to = matrix.rowOffsets[i + 1];
            for (int idx = from; idx < to; idx++) {
                Symbols.xorInto(accumulator, symbols[matrix.rowEntries[idx]]);
            }

            if (!Symbols.isZero(accumulator)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Decodes with {@link DecodeOptions#defaults()}. See
     * {@link #decode(byte[][], boolean[], DecodeOptions)}.
     */
    public DecodeResult decode(byte[][] symbols, boolean[] symbolPresent) {
        return decode(symbols, symbolPresent, DecodeOptions.defaults());
    }

    /**
     * Recovers missing symbols in place. {@code symbolPresent} marks which of the
     * {@code K + R} symbols currently hold valid bytes; present symbols are read for
     * their length, and recovered symbol slots are (re)allocated and filled.
     *
     * <p>Both {@code symbols} and {@code symbolPresent} are <b>mutated</b>: every symbol
     * the decode recovers is written back and marked present. Missing repair symbols
     * fall out of the same equations at no extra cost and are recovered too.
     *
     * <p>Decoding runs degree-one peeling to a fixed point and, only if source symbols
     * remain unknown, one complete residual GF(2) solve (SPEC.md section 6). It never
     * iterates between the two.
     *
     * <p>A received set that does not determine the source is <b>not</b> an error: the
     * returned {@link DecodeResult} reports {@link DecodeResult#isComplete()} false and
     * lists what is still missing. Exceptions signal malformed input only.
     *
     * @throws LdpcStaircaseException if the symbol array is structurally invalid.
     */
    public DecodeResult decode(byte[][] symbols, boolean[] symbolPresent, DecodeOptions options) {
        if (options == null) {
            throw new LdpcStaircaseException("decode: options must not be null.");
        }

        int symbolLength = validatePresentSymbols(symbols, symbolPresent);
        final int k = sourceSymbolCount;
        final int n = encodingSymbolCount;

        int missingAtEntry = 0;
        for (int c = 0; c < n; c++) {
            if (!symbolPresent[c]) {
                missingAtEntry++;
            }
        }

        ProgressListener listener = options.progressListener();
        if (missingAtEntry == 0) {
            if (listener != null) {
                listener.onProgress(1, 1);
            }

            return new DecodeResult(true, DecodeResult.Stage.NONE, 0, 0, NO_INDICES, false, 0, 0, -1);
        }

        // Work model for the progress fraction: the peeling pass costs about one visit
        // per matrix entry, and room is reserved up front for a residual solve because
        // whether one is needed is only known after peeling stalls.
        long peelCost = matrix.entryCount() + 1L;
        long residualCost = options.residualSolveEnabled() ? (long) missingAtEntry * missingAtEntry : 0L;
        Progress progress = (listener == null) ? null : new Progress(listener, peelCost + residualCost);

        DecodeState state = new DecodeState(symbols, symbolPresent, symbolLength);
        peel(state, progress, peelCost);

        int missingSource = countMissingSource(symbolPresent, k);
        if (missingSource == 0) {
            if (progress != null) {
                progress.done();
            }

            return new DecodeResult(true, DecodeResult.Stage.PEELING, state.recoveredSource,
                    state.recoveredTotal, NO_INDICES, false, 0, 0, -1);
        }

        // Peeling stalled. Everything still unknown is a variable of the residual
        // system: after the fixed point no row has exactly one unknown, and rows with
        // none say nothing about the rest.
        int residualUnknowns = 0;
        for (int c = 0; c < n; c++) {
            if (!symbolPresent[c]) {
                residualUnknowns++;
            }
        }

        int residualRows = 0;
        for (int i = 0; i < repairSymbolCount; i++) {
            if (state.unknown[i] >= 2) {
                residualRows++;
            }
        }

        if (!options.residualSolveEnabled() || residualUnknowns > options.maxResidualUnknowns()) {
            if (progress != null) {
                progress.done();
            }

            return new DecodeResult(false, DecodeResult.Stage.PEELING, state.recoveredSource,
                    state.recoveredTotal, missingSourceIndices(symbolPresent, k, missingSource), true,
                    residualUnknowns, residualRows, -1);
        }

        int rank = residualSolve(state, residualUnknowns, residualRows, progress, peelCost, residualCost);

        missingSource = countMissingSource(symbolPresent, k);
        if (progress != null) {
            progress.done();
        }

        return new DecodeResult(missingSource == 0, DecodeResult.Stage.RESIDUAL_SOLVE,
                state.recoveredSource, state.recoveredTotal,
                missingSourceIndices(symbolPresent, k, missingSource), false, residualUnknowns,
                residualRows, rank);
    }

    /**
     * Splits {@code data} into {@code sourceSymbolCount} zero-padded symbols of
     * {@code symbolSize} bytes each. {@code symbolSize} must be positive and
     * {@code data.length <= sourceSymbolCount * symbolSize}.
     *
     * @throws LdpcStaircaseException if the arguments are out of range.
     */
    public static byte[][] split(byte[] data, int sourceSymbolCount, int symbolSize) {
        if (data == null) {
            throw new LdpcStaircaseException("split: data must not be null.");
        }

        if (sourceSymbolCount < 1) {
            throw new LdpcStaircaseException(
                    "split: sourceSymbolCount (K) must be >= 1; got " + sourceSymbolCount + ".");
        }

        if (symbolSize <= 0) {
            throw new LdpcStaircaseException("split: symbolSize must be > 0; got " + symbolSize + ".");
        }

        long capacity = (long) sourceSymbolCount * symbolSize;
        if (data.length > capacity) {
            throw new LdpcStaircaseException(
                    "split: data length " + data.length + " exceeds capacity " + capacity
                            + " (sourceSymbolCount * symbolSize).");
        }

        byte[][] symbols = new byte[sourceSymbolCount][];
        for (int i = 0; i < sourceSymbolCount; i++) {
            byte[] symbol = new byte[symbolSize];
            int offset = i * symbolSize;
            int remaining = data.length - offset;
            if (remaining > 0) {
                System.arraycopy(data, offset, symbol, 0, Math.min(remaining, symbolSize));
            }

            symbols[i] = symbol;
        }

        return symbols;
    }

    /**
     * Concatenates {@code sourceSymbols} in order and returns the first
     * {@code outputLength} bytes.
     *
     * @throws LdpcStaircaseException if {@code outputLength} is negative or exceeds the
     *     concatenated length.
     */
    public static byte[] join(byte[][] sourceSymbols, int outputLength) {
        if (sourceSymbols == null) {
            throw new LdpcStaircaseException("join: sourceSymbols must not be null.");
        }

        if (outputLength < 0) {
            throw new LdpcStaircaseException("join: outputLength must be >= 0; got " + outputLength + ".");
        }

        long total = 0;
        for (int i = 0; i < sourceSymbols.length; i++) {
            if (sourceSymbols[i] == null) {
                throw new LdpcStaircaseException("join: sourceSymbols[" + i + "] must not be null.");
            }

            total += sourceSymbols[i].length;
        }

        if (outputLength > total) {
            throw new LdpcStaircaseException(
                    "join: outputLength " + outputLength + " exceeds concatenated length " + total + ".");
        }

        byte[] output = new byte[outputLength];
        int written = 0;
        for (int i = 0; i < sourceSymbols.length && written < outputLength; i++) {
            byte[] symbol = sourceSymbols[i];
            int copy = Math.min(symbol.length, outputLength - written);
            System.arraycopy(symbol, 0, output, written, copy);
            written += copy;
        }

        return output;
    }

    // ------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------

    /**
     * Per-decode mutable state: for every equation, how many of its variables are still
     * unknown and the XOR of the ones that are known. When {@code unknown[i]} falls to
     * one, {@code accumulator[i]} <em>is</em> the value of the remaining variable, so
     * the accumulator buffer is handed straight over as the recovered symbol.
     */
    private final class DecodeState {
        final byte[][] symbols;
        final boolean[] present;
        final int[] unknown;
        final byte[][] accumulator;
        final int symbolLength;
        int recoveredSource;
        int recoveredTotal;

        DecodeState(byte[][] symbols, boolean[] present, int symbolLength) {
            this.symbols = symbols;
            this.present = present;
            this.unknown = new int[repairSymbolCount];
            this.accumulator = new byte[repairSymbolCount][];
            this.symbolLength = symbolLength;

            final int[] rowOffsets = matrix.rowOffsets;
            final int[] rowEntries = matrix.rowEntries;
            for (int i = 0; i < repairSymbolCount; i++) {
                int from = rowOffsets[i];
                int to = rowOffsets[i + 1];
                int missing = 0;
                for (int idx = from; idx < to; idx++) {
                    if (!present[rowEntries[idx]]) {
                        missing++;
                    }
                }

                unknown[i] = missing;
                if (missing == 0) {
                    continue; // A fully known row can never contribute again.
                }

                byte[] accumulated = new byte[symbolLength];
                for (int idx = from; idx < to; idx++) {
                    int c = rowEntries[idx];
                    if (present[c]) {
                        Symbols.xorInto(accumulated, symbols[c]);
                    }
                }

                accumulator[i] = accumulated;
            }
        }

        void recover(int variable, byte[] value) {
            symbols[variable] = value;
            present[variable] = true;
            recoveredTotal++;
            if (variable < sourceSymbolCount) {
                recoveredSource++;
            }
        }
    }

    /** SPEC.md section 6.1 — degree-one peeling to a fixed point. */
    private void peel(DecodeState state, Progress progress, long peelCost) {
        final int[] rowOffsets = matrix.rowOffsets;
        final int[] rowEntries = matrix.rowEntries;
        final int[] columnOffsets = matrix.columnOffsets;
        final int[] columnEntries = matrix.columnEntries;
        final int[] unknown = state.unknown;
        final byte[][] accumulator = state.accumulator;

        // A row is pushed only on the transition to exactly one unknown, and `unknown`
        // never rises, so one slot per row is enough.
        int[] stack = new int[Math.max(1, repairSymbolCount)];
        int top = 0;
        for (int i = 0; i < repairSymbolCount; i++) {
            if (unknown[i] == 1) {
                stack[top++] = i;
            }
        }

        long work = 0;
        while (top > 0) {
            int row = stack[--top];
            if (unknown[row] != 1) {
                continue; // Another equation already resolved this row's last unknown.
            }

            int rowFrom = rowOffsets[row];
            int rowTo = rowOffsets[row + 1];
            int variable = -1;
            for (int idx = rowFrom; idx < rowTo; idx++) {
                if (!state.present[rowEntries[idx]]) {
                    variable = rowEntries[idx];
                    break;
                }
            }

            byte[] value = accumulator[row];
            accumulator[row] = null;
            unknown[row] = 0;
            if (variable < 0) {
                continue; // Defensive: the count and the present-mask disagreed.
            }

            state.recover(variable, value);

            int columnFrom = columnOffsets[variable];
            int columnTo = columnOffsets[variable + 1];
            for (int idx = columnFrom; idx < columnTo; idx++) {
                int other = columnEntries[idx];
                if (other == row) {
                    continue;
                }

                int remaining = --unknown[other];
                if (remaining == 0) {
                    accumulator[other] = null; // Fully known; its accumulator is dead.
                    continue;
                }

                if (accumulator[other] != null) {
                    Symbols.xorInto(accumulator[other], value);
                }

                if (remaining == 1) {
                    if (top == stack.length) {
                        stack = Arrays.copyOf(stack, top * 2);
                    }

                    stack[top++] = other;
                }
            }

            if (progress != null) {
                work += (rowTo - rowFrom) + (columnTo - columnFrom);
                progress.set(Math.min(work, peelCost));
            }
        }
    }

    /**
     * SPEC.md section 6.2 — one complete residual solve.
     *
     * <p>The rows that still hold two or more unknowns form a GF(2) system in those
     * unknowns, with each row's accumulator as its right-hand side. Reducing it to
     * reduced row echelon form — applying every row operation to the symbol right-hand
     * sides as well — recovers exactly those variables the received set determines: a
     * pivot variable is known when its RREF row mentions no other variable. Anything
     * left is genuinely undetermined, which is why no second peeling pass could help.
     *
     * @return the rank of the residual system.
     */
    private int residualSolve(DecodeState state, int unknownCount, int rowCount, Progress progress,
            long peelCost, long residualCost) {
        final int n = encodingSymbolCount;

        // Map each still-unknown symbol index onto a column of the residual system.
        int[] columnOfSymbol = new int[n];
        Arrays.fill(columnOfSymbol, -1);
        int[] symbolOfColumn = new int[unknownCount];
        int next = mapUnknownColumns(state, columnOfSymbol, symbolOfColumn,
                sourceSymbolCount, n, 0);
        mapUnknownColumns(state, columnOfSymbol, symbolOfColumn, 0, sourceSymbolCount, next);

        int words = (unknownCount + 63) >>> 6;
        long[][] coefficients = new long[rowCount][];
        long[][] rightHandSides = new long[rowCount][];
        int filled = 0;
        for (int i = 0; i < repairSymbolCount && filled < rowCount; i++) {
            if (state.unknown[i] < 2) {
                continue;
            }

            long[] bits = new long[words];
            int from = matrix.rowOffsets[i];
            int to = matrix.rowOffsets[i + 1];
            for (int idx = from; idx < to; idx++) {
                int column = columnOfSymbol[matrix.rowEntries[idx]];
                if (column >= 0) {
                    bits[column >>> 6] |= 1L << (column & 63);
                }
            }

            coefficients[filled] = bits;
            rightHandSides[filled] = Symbols.toLongWords(state.accumulator[i]);
            state.accumulator[i] = null;
            filled++;
        }

        // Gauss-Jordan to reduced row echelon form.
        int rank = 0;
        int[] pivotColumn = new int[Math.min(rowCount, unknownCount)];
        for (int column = 0; column < unknownCount && rank < rowCount; column++) {
            int word = column >>> 6;
            long mask = 1L << (column & 63);

            int pivot = -1;
            for (int row = rank; row < rowCount; row++) {
                if ((coefficients[row][word] & mask) != 0) {
                    pivot = row;
                    break;
                }
            }

            if (pivot < 0) {
                continue; // Free variable: no equation can still resolve it alone.
            }

            if (pivot != rank) {
                long[] bits = coefficients[pivot];
                coefficients[pivot] = coefficients[rank];
                coefficients[rank] = bits;
                long[] rhs = rightHandSides[pivot];
                rightHandSides[pivot] = rightHandSides[rank];
                rightHandSides[rank] = rhs;
            }

            for (int row = 0; row < rowCount; row++) {
                if (row == rank || (coefficients[row][word] & mask) == 0) {
                    continue;
                }

                Symbols.xorInto(coefficients[row], coefficients[rank]);
                Symbols.xorInto(rightHandSides[row], rightHandSides[rank]);
            }

            pivotColumn[rank] = column;
            rank++;

            if (progress != null) {
                // Divide before multiplying: residualCost is already quadratic in the
                // missing count, and the product would be the only overflow risk here.
                progress.set(peelCost + residualCost / unknownCount * (column + 1L));
            }
        }

        // A pivot row whose only remaining coefficient is its own pivot determines that
        // variable outright; one that still mentions a free variable does not.
        for (int row = 0; row < rank; row++) {
            long[] bits = coefficients[row];
            int ones = 0;
            for (int w = 0; w < words && ones < 2; w++) {
                ones += Long.bitCount(bits[w]);
            }

            if (ones != 1) {
                continue;
            }

            state.recover(symbolOfColumn[pivotColumn[row]],
                    Symbols.fromLongWords(rightHandSides[row], state.symbolLength));
        }

        return rank;
    }

    /**
     * Adds unknown symbols in {@code [from, to)} to the residual column mapping.
     * Repair symbols are mapped before source symbols: a staircase repair variable
     * occurs in at most two parity equations, so eliminating it first preserves the
     * sparse structure instead of prematurely filling the matrix while pivoting on
     * higher-degree source variables.
     */
    private static int mapUnknownColumns(DecodeState state, int[] columnOfSymbol,
            int[] symbolOfColumn, int from, int to, int next) {
        for (int symbol = from; symbol < to; symbol++) {
            if (!state.present[symbol]) {
                columnOfSymbol[symbol] = next;
                symbolOfColumn[next] = symbol;
                next++;
            }
        }

        return next;
    }

    private static int countMissingSource(boolean[] present, int sourceSymbolCount) {
        int missing = 0;
        for (int j = 0; j < sourceSymbolCount; j++) {
            if (!present[j]) {
                missing++;
            }
        }

        return missing;
    }

    private static int[] missingSourceIndices(boolean[] present, int sourceSymbolCount, int missing) {
        if (missing == 0) {
            return NO_INDICES;
        }

        int[] indices = new int[missing];
        int next = 0;
        for (int j = 0; j < sourceSymbolCount && next < missing; j++) {
            if (!present[j]) {
                indices[next++] = j;
            }
        }

        return indices;
    }

    /**
     * Throttled progress relay: forwards at most ~200 {@link ProgressListener#onProgress}
     * calls over one decode, clamps to {@code [0, total]}, and guarantees one final
     * exact-total tick via {@link #done()}.
     */
    private static final class Progress {
        private final ProgressListener listener;
        private final long total;
        private final long minStep;
        private long lastEmitted = -1;

        Progress(ProgressListener listener, long total) {
            this.listener = listener;
            this.total = Math.max(1, total);
            this.minStep = Math.max(1, this.total / 200);
        }

        void set(long completed) {
            long clamped = Math.min(Math.max(completed, 0), total);
            if (clamped < total && clamped - lastEmitted >= minStep) {
                lastEmitted = clamped;
                listener.onProgress(clamped, total);
            }
        }

        void done() {
            lastEmitted = total;
            listener.onProgress(total, total);
        }
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    private static byte[] ensureSymbol(byte[][] symbols, int index, int symbolLength) {
        byte[] symbol = symbols[index];
        if (symbol == null || symbol.length != symbolLength) {
            symbol = new byte[symbolLength];
            symbols[index] = symbol;
        }

        return symbol;
    }

    /** Validates the {@code K} source slots of an encode array; returns their length. */
    private int validateSourceSymbols(byte[][] symbols) {
        requireArrayLength(symbols, "encode");

        int symbolLength = -1;
        for (int i = 0; i < sourceSymbolCount; i++) {
            byte[] symbol = symbols[i];
            if (symbol == null) {
                throw new LdpcStaircaseException("encode: source symbol " + i + " must not be null.");
            }

            if (symbolLength < 0) {
                symbolLength = requirePositiveLength(symbol.length, "encode");
            } else if (symbol.length != symbolLength) {
                throw new LdpcStaircaseException(
                        "encode: all source symbols must have equal length; symbol " + i + " has length "
                                + symbol.length + " but expected " + symbolLength + ".");
            }
        }

        return symbolLength;
    }

    /** Validates a fully populated symbol array; returns the common symbol length. */
    private int validateAllSymbols(byte[][] symbols) {
        requireArrayLength(symbols, "verify");

        int symbolLength = -1;
        for (int i = 0; i < encodingSymbolCount; i++) {
            byte[] symbol = symbols[i];
            if (symbol == null) {
                throw new LdpcStaircaseException("verify: symbol " + i + " must not be null.");
            }

            if (symbolLength < 0) {
                symbolLength = requirePositiveLength(symbol.length, "verify");
            } else if (symbol.length != symbolLength) {
                throw new LdpcStaircaseException(
                        "verify: all symbols must have equal length; symbol " + i + " has length "
                                + symbol.length + " but expected " + symbolLength + ".");
            }
        }

        return symbolLength;
    }

    /** Validates the present symbols of a decode array; returns their common length. */
    private int validatePresentSymbols(byte[][] symbols, boolean[] symbolPresent) {
        requireArrayLength(symbols, "decode");

        if (symbolPresent == null) {
            throw new LdpcStaircaseException("decode: symbolPresent must not be null.");
        }

        if (symbolPresent.length != encodingSymbolCount) {
            throw new LdpcStaircaseException(
                    "decode: symbolPresent must have " + encodingSymbolCount + " entries (K + R); got "
                            + symbolPresent.length + ".");
        }

        int symbolLength = -1;
        for (int i = 0; i < encodingSymbolCount; i++) {
            if (!symbolPresent[i]) {
                continue;
            }

            byte[] symbol = symbols[i];
            if (symbol == null) {
                throw new LdpcStaircaseException(
                        "decode: symbol " + i + " is marked present but is null.");
            }

            if (symbolLength < 0) {
                symbolLength = requirePositiveLength(symbol.length, "decode");
            } else if (symbol.length != symbolLength) {
                throw new LdpcStaircaseException(
                        "decode: present symbols have differing lengths (" + symbolLength + " vs "
                                + symbol.length + ").");
            }
        }

        if (symbolLength < 0) {
            throw new LdpcStaircaseException("decode: at least one symbol must be present.");
        }

        return symbolLength;
    }

    private void requireArrayLength(byte[][] symbols, String operation) {
        if (symbols == null) {
            throw new LdpcStaircaseException(operation + ": symbols must not be null.");
        }

        if (symbols.length != encodingSymbolCount) {
            throw new LdpcStaircaseException(operation + ": expected " + encodingSymbolCount
                    + " symbols (K + R); got " + symbols.length + ".");
        }
    }

    private static int requirePositiveLength(int symbolLength, String operation) {
        if (symbolLength <= 0) {
            throw new LdpcStaircaseException(
                    operation + ": symbol length must be > 0; got " + symbolLength + ".");
        }

        return symbolLength;
    }

    @Override
    public String toString() {
        return "LdpcStaircase{K=" + sourceSymbolCount + ", R=" + repairSymbolCount
                + ", N1=" + matrix.leftDegree() + ", seed=" + matrix.seed() + "}";
    }
}
