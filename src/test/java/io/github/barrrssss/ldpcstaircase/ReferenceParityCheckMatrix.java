package io.github.barrrssss.ldpcstaircase;

import java.util.Arrays;

/**
 * Test-only reference build of {@code H}, kept verbatim from the object-forest
 * implementation that preceded the the shared specification flat primitive storage.
 *
 * <p>Its only purpose is differential coverage: {@link FlatGraphParityTest} asserts
 * that the production {@link ParityCheckMatrix} agrees with this reference row for row,
 * column for column, and byte for byte across boundary, random and calibrated cells.
 * It intentionally allocates a growable object per row and per column — that is exactly
 * the shape the shared specification removed from production, so it must never be used outside tests.
 */
final class ReferenceParityCheckMatrix {

    final int[][] rowColumns;
    final int[][] columnRows;
    final int entryCount;

    private ReferenceParityCheckMatrix(int[][] rowColumns, int[][] columnRows, int entryCount) {
        this.rowColumns = rowColumns;
        this.columnRows = columnRows;
        this.entryCount = entryCount;
    }

    static ReferenceParityCheckMatrix build(int sourceSymbolCount, int repairSymbolCount,
            int leftDegree, int seed) {
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

        return new ReferenceParityCheckMatrix(rowColumns, columnRows, entries);
    }

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
                int i = t;
                while (i < pool && columns[j].contains(u[i])) {
                    i++;
                }

                if (i < pool) {
                    do {
                        i = t + rng.nextBounded(pool - t);
                    } while (columns[j].contains(u[i]));

                    set(rows, columns, u[i], j);
                    u[i] = u[t];
                    t++;
                } else {
                    int row;
                    do {
                        row = rng.nextBounded(r);
                    } while (columns[j].contains(row));

                    set(rows, columns, row, j);
                }
            }
        }
    }

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

    /** The canonical serialization of SPEC.md section 7.1, rebuilt from the reference graph. */
    byte[] serialize(int repairSymbolCount, int encodingSymbolCount) {
        byte[] out = new byte[4 * (2 + repairSymbolCount + entryCount)];
        int p = 0;
        p = writeU32(out, p, repairSymbolCount);
        p = writeU32(out, p, encodingSymbolCount);
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
