package io.github.barrrssss.ldpcstaircase;

import java.util.Arrays;

/**
 * The sparse binary parity check matrix {@code H} of SPEC.md section 4: {@code R}
 * rows (one per parity equation) by {@code N = K + R} columns (one per encoding
 * symbol). Columns {@code 0..K-1} are the source symbols; columns {@code K..N-1} are
 * the repair symbols, carrying the staircase.
 *
 * <p>Each row is one equation constraining its symbols to XOR to zero. The matrix is
 * a pure function of {@code (K, R, N1, seed)}: it is built by three ordered phases
 * that share a single {@link PmmsRandom} stream, so two implementations given the
 * same four values build the same graph. That order is normative — it fixes the
 * sequence of PRNG draws.
 *
 * <p>The structure is stored twice, by row and by column, because the encoder walks
 * rows and the decoder walks the rows containing a newly recovered symbol. Both
 * index lists are sorted ascending.
 *
 * <h2>Storage</h2>
 *
 * <p>Adjacency lives in four flat primitive arrays in compressed-sparse form rather
 * than a {@code int[][]} forest:
 *
 * <pre>
 * rowOffsets[R + 1]      rowEntries[entryCount]
 * columnOffsets[N + 1]   columnEntries[entryCount]
 * </pre>
 *
 * <p>Row {@code i} occupies {@code rowEntries[rowOffsets[i] .. rowOffsets[i+1])};
 * columns use the same rule. There is no per-row or per-column heap object, which on
 * a weak Android heap removes {@code 2 * (R + N)} array headers plus the growable
 * builders the previous construction allocated. Range lookup stays O(1) and the hot
 * encode/decode loops traverse the flat arrays directly.
 *
 * <p>Construction is a deterministic two-pass build over bounded primitive work
 * buffers. Pass one replays the normative edge-generation sequence exactly — same
 * PRNG draws in the same order — into a primitive edge list threaded by column so the
 * duplicate-edge test stays a short chain walk; pass two prefix-sums the degrees and
 * fills both adjacency directions. No PRNG draw was added, removed, or reordered to
 * make this representation convenient.
 */
public final class ParityCheckMatrix {

    /** The default profile value for {@code N1}. */
    public static final int DEFAULT_LEFT_DEGREE = 7;

    private final int sourceSymbolCount;
    private final int repairSymbolCount;
    private final int leftDegree;
    private final int seed;
    private final int entryCount;

    // Package-private: LdpcStaircase reads these directly in its hot loops. The public
    // accessors below hand out copies so callers cannot corrupt the matrix.
    final int[] rowOffsets;
    final int[] rowEntries;
    final int[] columnOffsets;
    final int[] columnEntries;

    private ParityCheckMatrix(int sourceSymbolCount, int repairSymbolCount, int leftDegree, int seed,
            int[] rowOffsets, int[] rowEntries, int[] columnOffsets, int[] columnEntries,
            int entryCount) {
        this.sourceSymbolCount = sourceSymbolCount;
        this.repairSymbolCount = repairSymbolCount;
        this.leftDegree = leftDegree;
        this.seed = seed;
        this.rowOffsets = rowOffsets;
        this.rowEntries = rowEntries;
        this.columnOffsets = columnOffsets;
        this.columnEntries = columnEntries;
        this.entryCount = entryCount;
    }

    /** Number of source symbols, {@code K}. */
    public int sourceSymbolCount() {
        return sourceSymbolCount;
    }

    /** Number of repair symbols, {@code R}. Also the number of equations (rows). */
    public int repairSymbolCount() {
        return repairSymbolCount;
    }

    /** Number of encoding symbols, {@code N = K + R}. Also the number of columns. */
    public int encodingSymbolCount() {
        return sourceSymbolCount + repairSymbolCount;
    }

    /** The configured number of ones per source column, {@code N1}. */
    public int leftDegree() {
        return leftDegree;
    }

    /** The PRNG seed the matrix was built from. */
    public int seed() {
        return seed;
    }

    /** Total number of ones in {@code H}. */
    public int entryCount() {
        return entryCount;
    }

    /** The column indices of the ones in {@code row}, ascending. Returns a copy. */
    public int[] rowColumns(int row) {
        return Arrays.copyOfRange(rowEntries, rowOffsets[row], rowOffsets[row + 1]);
    }

    /** The row indices of the ones in {@code column}, ascending. Returns a copy. */
    public int[] columnRows(int column) {
        return Arrays.copyOfRange(columnEntries, columnOffsets[column], columnOffsets[column + 1]);
    }

    /** Number of ones in {@code row}. */
    public int rowDegree(int row) {
        return rowOffsets[row + 1] - rowOffsets[row];
    }

    /** Number of ones in {@code column}. */
    public int columnDegree(int column) {
        return columnOffsets[column + 1] - columnOffsets[column];
    }

    /**
     * Builds {@code H} for {@code (K, R, N1, seed)} following SPEC.md section 4.
     *
     * @param sourceSymbolCount {@code K}, at least 1
     * @param repairSymbolCount {@code R}, at least 0 ({@code 0} yields the empty
     *     matrix — the no-repair bypass)
     * @param leftDegree {@code N1}, at least 1 and at most {@code R} when {@code R >= 1}
     * @param seed the PRNG seed, in {@code [1, 2147483646]}
     * @throws LdpcStaircaseException if any parameter is out of range.
     */
    public static ParityCheckMatrix build(int sourceSymbolCount, int repairSymbolCount, int leftDegree,
            int seed) {
        validate(sourceSymbolCount, repairSymbolCount, leftDegree, seed);

        final int k = sourceSymbolCount;
        final int r = repairSymbolCount;
        final int n = k + r;

        if (r == 0) {
            return new ParityCheckMatrix(k, r, leftDegree, seed, new int[1], new int[0],
                    new int[n + 1], new int[0], 0);
        }

        Builder builder = new Builder(k, r, leftDegree);
        PmmsRandom rng = new PmmsRandom(seed);
        fillLeftPart(builder, k, r, leftDegree, rng);
        raiseLowDegreeRows(builder, k, r, rng);
        fillStaircase(builder, k, r);

        return builder.finish(k, r, leftDegree, seed);
    }

    /**
     * SPEC.md section 4.2 — place {@code N1} ones in every source column.
     *
     * <p>The pool {@code u} holds {@code N1 * K} row indices cycling over
     * {@code 0..R-1} so that draws are spread evenly over the equations; {@code t}
     * separates the consumed prefix from the still-available suffix. Drawing from the
     * suffix and swap-removing the chosen slot is what keeps row degrees balanced.
     */
    private static void fillLeftPart(Builder builder, int k, int r, int n1, PmmsRandom rng) {
        final int pool = n1 * k;
        int[] u = new int[pool];
        for (int h = pool - 1; h >= 0; h--) {
            u[h] = h % r;
        }

        int t = 0;
        for (int j = 0; j < k; j++) {
            for (int h = 0; h < n1; h++) {
                // Does any unconsumed pool slot still name a row this column lacks?
                // Scanning first is what guarantees the draw loop below terminates.
                int i = t;
                while (i < pool && builder.columnContains(j, u[i])) {
                    i++;
                }

                if (i < pool) {
                    do {
                        i = t + rng.nextBounded(pool - t);
                    } while (builder.columnContains(j, u[i]));

                    builder.set(u[i], j);
                    u[i] = u[t]; // swap-remove: u[t] has never been chosen
                    t++;
                } else {
                    // The pool is exhausted for this column; fall back to a free row.
                    // Terminates because N1 <= R, so some row is always free.
                    int row;
                    do {
                        row = rng.nextBounded(r);
                    } while (builder.columnContains(j, row));

                    builder.set(row, j);
                }
            }
        }
    }

    /**
     * SPEC.md section 4.3 — raise rows with fewer than two ones in the left part.
     * Such rows are pathological for the decoder. The two checks are sequential, not
     * exclusive: a degree-0 row is raised to 1 by the first and to 2 by the second.
     *
     * <p>Row degrees here count left-part entries only, which is automatic: the
     * staircase has not been added yet.
     *
     * <p>The {@code k >= 2} guard is this specification's degenerate-case rule; RFC 5170
     * assumes a second distinct column always exists.
     */
    private static void raiseLowDegreeRows(Builder builder, int k, int r, PmmsRandom rng) {
        for (int i = 0; i < r; i++) {
            if (builder.rowDegree(i) == 0) {
                builder.set(i, rng.nextBounded(k));
            }

            if (builder.rowDegree(i) == 1 && k >= 2) {
                int j;
                do {
                    j = rng.nextBounded(k);
                } while (builder.columnContains(j, i));

                builder.set(i, j);
            }
        }
    }

    /**
     * SPEC.md section 4.4 — the staircase over the repair columns. Row {@code i} holds
     * repair symbol {@code i} and (except for row 0) repair symbol {@code i - 1}, which
     * is what makes encoding a running XOR. Consumes no PRNG draws.
     */
    private static void fillStaircase(Builder builder, int k, int r) {
        builder.set(0, k);
        for (int i = 1; i < r; i++) {
            builder.set(i, k + i);
            builder.set(i, k + i - 1);
        }
    }

    private static void validate(int sourceSymbolCount, int repairSymbolCount, int leftDegree, int seed) {
        if (sourceSymbolCount < 1) {
            throw new LdpcStaircaseException(
                    "sourceSymbolCount (K) must be >= 1; got " + sourceSymbolCount + ".");
        }

        if (repairSymbolCount < 0) {
            throw new LdpcStaircaseException(
                    "repairSymbolCount (R) must be >= 0; got " + repairSymbolCount + ".");
        }

        if ((long) sourceSymbolCount + repairSymbolCount > Integer.MAX_VALUE) {
            throw new LdpcStaircaseException(
                    "sourceSymbolCount + repairSymbolCount (K + R) must be <= " + Integer.MAX_VALUE
                            + "; got " + ((long) sourceSymbolCount + repairSymbolCount) + ".");
        }

        if (leftDegree < 1) {
            throw new LdpcStaircaseException("leftDegree (N1) must be >= 1; got " + leftDegree + ".");
        }

        if (repairSymbolCount >= 1 && leftDegree > repairSymbolCount) {
            // Each source column needs N1 *distinct* rows; without this the RFC
            // construction cannot terminate.
            throw new LdpcStaircaseException(
                    "leftDegree (N1) must be <= repairSymbolCount (R) when R >= 1; got N1="
                            + leftDegree + ", R=" + repairSymbolCount + ".");
        }

        if ((long) leftDegree * sourceSymbolCount > Integer.MAX_VALUE) {
            throw new LdpcStaircaseException(
                    "leftDegree * sourceSymbolCount (N1 * K) must be <= " + Integer.MAX_VALUE
                            + "; got " + ((long) leftDegree * sourceSymbolCount) + ".");
        }

        // The construction emits at most N1*K left-part edges, 2R low-degree corrections
        // and 2R-1 staircase edges. The flat builder sizes its work buffers from that
        // exact bound, so it has to fit an int.
        long maxEdges = (long) leftDegree * sourceSymbolCount + 4L * repairSymbolCount;
        if (maxEdges > Integer.MAX_VALUE) {
            throw new LdpcStaircaseException(
                    "N1 * K + 4R must be <= " + Integer.MAX_VALUE + "; got " + maxEdges + ".");
        }

        if (seed < PmmsRandom.MIN_SEED || seed > PmmsRandom.MAX_SEED) {
            throw new LdpcStaircaseException(
                    "seed must be in [" + PmmsRandom.MIN_SEED + ", " + PmmsRandom.MAX_SEED + "]; got "
                            + seed + ".");
        }
    }

    /**
     * The canonical serialization of SPEC.md section 7.1: big-endian {@code u32} row
     * count and column count, then per row its degree followed by its ascending column
     * indices. This is the byte form the {@code matrix} conformance vectors compare.
     */
    public byte[] serialize() {
        int n = encodingSymbolCount();
        byte[] out = new byte[4 * (2 + repairSymbolCount + entryCount)];
        int p = 0;
        p = writeU32(out, p, repairSymbolCount);
        p = writeU32(out, p, n);
        for (int i = 0; i < repairSymbolCount; i++) {
            int from = rowOffsets[i];
            int to = rowOffsets[i + 1];
            p = writeU32(out, p, to - from);
            for (int idx = from; idx < to; idx++) {
                p = writeU32(out, p, rowEntries[idx]);
            }
        }

        return out;
    }

    private static int writeU32(byte[] out, int offset, int value) {
        out[offset] = (byte) (value >>> 24);
        out[offset + 1] = (byte) (value >>> 16);
        out[offset + 2] = (byte) (value >>> 8);
        out[offset + 3] = (byte) value;
        return offset + 4;
    }

    @Override
    public String toString() {
        return "ParityCheckMatrix{K=" + sourceSymbolCount + ", R=" + repairSymbolCount
                + ", N1=" + leftDegree + ", seed=" + seed + ", entries=" + entryCount + "}";
    }

    /**
     * Edge accumulator for the generation pass, backed only by primitive arrays.
     *
     * <p>Edges are appended in normative generation order. Each is threaded onto a
     * singly linked list for its column ({@code columnHead} + {@code nextInColumn}), which
     * is what makes {@link #columnContains} — the duplicate-edge test the RFC construction
     * performs on every draw — a short walk instead of a set lookup. Chain order is
     * irrelevant because it is only ever used for membership, and both adjacency
     * directions are sorted in {@link #finish}.
     *
     * <p>All buffers are sized from the exact upper bound {@code N1*K + 4R}, so the build
     * never grows or reallocates.
     */
    private static final class Builder {
        private final int[] edgeRow;
        private final int[] nextInColumn;
        private final int[] columnHead;
        private final int[] rowDegree;
        private final int[] columnDegree;
        private int edgeCount;

        Builder(int k, int r, int n1) {
            int n = k + r;
            int maxEdges = n1 * k + 4 * r;
            this.edgeRow = new int[maxEdges];
            this.nextInColumn = new int[maxEdges];
            this.columnHead = new int[n];
            Arrays.fill(this.columnHead, -1);
            this.rowDegree = new int[r];
            this.columnDegree = new int[n];
        }

        void set(int row, int column) {
            int edge = edgeCount++;
            edgeRow[edge] = row;
            nextInColumn[edge] = columnHead[column];
            columnHead[column] = edge;
            rowDegree[row]++;
            columnDegree[column]++;
        }

        /** Whether {@code column} already holds a one in {@code row}. */
        boolean columnContains(int column, int row) {
            // Chains are short: a source column holds N1 entries, a repair column two.
            for (int edge = columnHead[column]; edge >= 0; edge = nextInColumn[edge]) {
                if (edgeRow[edge] == row) {
                    return true;
                }
            }

            return false;
        }

        int rowDegree(int row) {
            return rowDegree[row];
        }

        /** Prefix-sums the degrees and fills both adjacency directions, ascending. */
        ParityCheckMatrix finish(int k, int r, int n1, int seed) {
            int n = k + r;

            int[] rowOffsets = new int[r + 1];
            for (int i = 0; i < r; i++) {
                rowOffsets[i + 1] = rowOffsets[i] + rowDegree[i];
            }

            int[] columnOffsets = new int[n + 1];
            for (int c = 0; c < n; c++) {
                columnOffsets[c + 1] = columnOffsets[c] + columnDegree[c];
            }

            int entries = edgeCount;
            int[] rowEntries = new int[entries];
            int[] columnEntries = new int[entries];
            int[] rowCursor = Arrays.copyOf(rowOffsets, r);
            int[] columnCursor = Arrays.copyOf(columnOffsets, n);

            for (int c = 0; c < n; c++) {
                for (int edge = columnHead[c]; edge >= 0; edge = nextInColumn[edge]) {
                    int row = edgeRow[edge];
                    columnEntries[columnCursor[c]++] = row;
                    rowEntries[rowCursor[row]++] = c;
                }
            }

            // Ascending order is normative: the canonical serialization and the
            // conformance vectors compare row adjacency element by element.
            for (int i = 0; i < r; i++) {
                Arrays.sort(rowEntries, rowOffsets[i], rowOffsets[i + 1]);
            }

            for (int c = 0; c < n; c++) {
                Arrays.sort(columnEntries, columnOffsets[c], columnOffsets[c + 1]);
            }

            return new ParityCheckMatrix(k, r, n1, seed, rowOffsets, rowEntries, columnOffsets,
                    columnEntries, entries);
        }
    }
}
