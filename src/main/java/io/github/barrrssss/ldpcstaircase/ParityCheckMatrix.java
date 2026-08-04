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
    // accessors below hand out clones so callers cannot corrupt the matrix.
    final int[][] rowColumns;
    final int[][] columnRows;

    private ParityCheckMatrix(int sourceSymbolCount, int repairSymbolCount, int leftDegree, int seed,
            int[][] rowColumns, int[][] columnRows, int entryCount) {
        this.sourceSymbolCount = sourceSymbolCount;
        this.repairSymbolCount = repairSymbolCount;
        this.leftDegree = leftDegree;
        this.seed = seed;
        this.rowColumns = rowColumns;
        this.columnRows = columnRows;
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
        return rowColumns[row].clone();
    }

    /** The row indices of the ones in {@code column}, ascending. Returns a copy. */
    public int[] columnRows(int column) {
        return columnRows[column].clone();
    }

    /** Number of ones in {@code row}. */
    public int rowDegree(int row) {
        return rowColumns[row].length;
    }

    /** Number of ones in {@code column}. */
    public int columnDegree(int column) {
        return columnRows[column].length;
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

        IntList[] rows = new IntList[r];
        for (int i = 0; i < r; i++) {
            rows[i] = new IntList();
        }

        IntList[] columns = new IntList[n];
        for (int c = 0; c < n; c++) {
            columns[c] = new IntList();
        }

        if (r > 0) {
            PmmsRandom rng = new PmmsRandom(seed);
            fillLeftPart(rows, columns, k, r, leftDegree, rng);
            raiseLowDegreeRows(rows, columns, k, r, rng);
            fillStaircase(rows, columns, k, r);
        }

        int entries = 0;
        int[][] rowColumns = new int[r][];
        for (int i = 0; i < r; i++) {
            rowColumns[i] = rows[i].toSortedArray();
            entries += rowColumns[i].length;
        }

        int[][] columnRows = new int[n][];
        for (int c = 0; c < n; c++) {
            columnRows[c] = columns[c].toSortedArray();
        }

        return new ParityCheckMatrix(k, r, leftDegree, seed, rowColumns, columnRows, entries);
    }

    /**
     * SPEC.md section 4.2 — place {@code N1} ones in every source column.
     *
     * <p>The pool {@code u} holds {@code N1 * K} row indices cycling over
     * {@code 0..R-1} so that draws are spread evenly over the equations; {@code t}
     * separates the consumed prefix from the still-available suffix. Drawing from the
     * suffix and swap-removing the chosen slot is what keeps row degrees balanced.
     */
    private static void fillLeftPart(IntList[] rows, IntList[] columns, int k, int r, int n1,
            PmmsRandom rng) {
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
                while (i < pool && columns[j].contains(u[i])) {
                    i++;
                }

                if (i < pool) {
                    do {
                        i = t + rng.nextBounded(pool - t);
                    } while (columns[j].contains(u[i]));

                    set(rows, columns, u[i], j);
                    u[i] = u[t]; // swap-remove: u[t] has never been chosen
                    t++;
                } else {
                    // The pool is exhausted for this column; fall back to a free row.
                    // Terminates because N1 <= R, so some row is always free.
                    int row;
                    do {
                        row = rng.nextBounded(r);
                    } while (columns[j].contains(row));

                    set(rows, columns, row, j);
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
    private static void raiseLowDegreeRows(IntList[] rows, IntList[] columns, int k, int r,
            PmmsRandom rng) {
        for (int i = 0; i < r; i++) {
            if (rows[i].size() == 0) {
                set(rows, columns, i, rng.nextBounded(k));
            }

            if (rows[i].size() == 1 && k >= 2) {
                int j;
                do {
                    j = rng.nextBounded(k);
                } while (columns[j].contains(i));

                set(rows, columns, i, j);
            }
        }
    }

    /**
     * SPEC.md section 4.4 — the staircase over the repair columns. Row {@code i} holds
     * repair symbol {@code i} and (except for row 0) repair symbol {@code i - 1}, which
     * is what makes encoding a running XOR. Consumes no PRNG draws.
     */
    private static void fillStaircase(IntList[] rows, IntList[] columns, int k, int r) {
        set(rows, columns, 0, k);
        for (int i = 1; i < r; i++) {
            set(rows, columns, i, k + i);
            set(rows, columns, i, k + i - 1);
        }
    }

    private static void set(IntList[] rows, IntList[] columns, int row, int column) {
        rows[row].add(column);
        columns[column].add(row);
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
            int[] cols = rowColumns[i];
            p = writeU32(out, p, cols.length);
            for (int c : cols) {
                p = writeU32(out, p, c);
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
     * A minimal growable {@code int} list. The matrix is built as ~{@code N1 * K + 2R}
     * individual insertions across {@code R + N} lists, so avoiding {@code Integer}
     * boxing here is worth the few lines.
     */
    private static final class IntList {
        private int[] values = new int[4];
        private int size;

        void add(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }

            values[size++] = value;
        }

        boolean contains(int value) {
            // Lists are short (a source column holds N1 entries, a repair column two),
            // so a linear scan beats any hashed structure here.
            for (int i = 0; i < size; i++) {
                if (values[i] == value) {
                    return true;
                }
            }

            return false;
        }

        int size() {
            return size;
        }

        int[] toSortedArray() {
            int[] result = Arrays.copyOf(values, size);
            Arrays.sort(result);
            return result;
        }
    }
}
