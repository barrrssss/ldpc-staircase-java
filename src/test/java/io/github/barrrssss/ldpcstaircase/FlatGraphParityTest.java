package io.github.barrrssss.ldpcstaircase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Differential coverage for the flat primitive
 * {@link ParityCheckMatrix} must agree with the {@link ReferenceParityCheckMatrix}
 * object-forest build on every row, every column, the canonical serialization, and the
 * encoded repair bytes.
 *
 * <p>The reference is the exact pre-flat production algorithm, so any divergence in
 * PRNG consumption, duplicate-edge avoidance, low-degree-row correction, staircase
 * placement or adjacency ordering fails here rather than in a downstream vector.
 */
class FlatGraphParityTest {

    private static final int N1 = ParityCheckMatrix.DEFAULT_LEFT_DEGREE;

    /** Boundary shapes, calibrated release cells, and randomized cells. */
    static List<Cell> cells() {
        List<Cell> cells = new ArrayList<>();

        // Degenerate and boundary shapes.
        cells.add(new Cell(1, 0, 1, 1));
        cells.add(new Cell(1, 1, 1, 1));
        cells.add(new Cell(2, 1, 1, 7));
        cells.add(new Cell(1, 7, N1, 12345));
        cells.add(new Cell(2, 7, N1, 2147483646));
        cells.add(new Cell(7, 7, N1, 1));
        cells.add(new Cell(8, 7, N1, 99));

        // Calibrated and release-envelope cells.
        cells.add(new Cell(256, 13, N1, 4242));
        cells.add(new Cell(2939, 2939, N1, 20250812));
        cells.add(new Cell(10000, 5000, N1, 777));
        cells.add(new Cell(16000, 5600, N1, 31337));
        cells.add(new Cell(16000, 8000, N1, 8191));
        cells.add(new Cell(16000, 8192, N1, 2147483646));

        // Randomized cells over the supported envelope.
        Random random = new Random(0x5EEDF1A7L);
        for (int i = 0; i < 12; i++) {
            int k = 1 + random.nextInt(4000);
            int r = N1 + random.nextInt(2000);
            int seed = 1 + random.nextInt(PmmsRandom.MAX_SEED);
            cells.add(new Cell(k, r, N1, seed));
        }

        return cells;
    }

    @ParameterizedTest(name = "K={0}")
    @MethodSource("cells")
    @DisplayName("flat storage reproduces the reference graph exactly")
    void flatMatchesReference(Cell cell) {
        ParityCheckMatrix flat = ParityCheckMatrix.build(cell.k, cell.r, cell.n1, cell.seed);
        ReferenceParityCheckMatrix reference =
                ReferenceParityCheckMatrix.build(cell.k, cell.r, cell.n1, cell.seed);

        assertEquals(reference.entryCount, flat.entryCount(), "entry count for " + cell);

        for (int row = 0; row < cell.r; row++) {
            assertArrayEquals(reference.rowColumns[row], flat.rowColumns(row),
                    "row " + row + " of " + cell);
            assertEquals(reference.rowColumns[row].length, flat.rowDegree(row),
                    "row degree " + row + " of " + cell);
        }

        int n = cell.k + cell.r;
        for (int column = 0; column < n; column++) {
            assertArrayEquals(reference.columnRows[column], flat.columnRows(column),
                    "column " + column + " of " + cell);
            assertEquals(reference.columnRows[column].length, flat.columnDegree(column),
                    "column degree " + column + " of " + cell);
        }

        assertArrayEquals(reference.serialize(cell.r, n), flat.serialize(),
                "canonical serialization of " + cell);
    }

    @ParameterizedTest(name = "K={0}")
    @MethodSource("cells")
    @DisplayName("flat offsets stay internally consistent and ascending")
    void flatOffsetsAreWellFormed(Cell cell) {
        ParityCheckMatrix flat = ParityCheckMatrix.build(cell.k, cell.r, cell.n1, cell.seed);
        int n = cell.k + cell.r;

        assertEquals(0, flat.rowOffsets[0], "rowOffsets must start at zero");
        assertEquals(flat.entryCount(), flat.rowOffsets[cell.r], "rowOffsets must end at entryCount");
        assertEquals(0, flat.columnOffsets[0], "columnOffsets must start at zero");
        assertEquals(flat.entryCount(), flat.columnOffsets[n],
                "columnOffsets must end at entryCount");

        for (int row = 0; row < cell.r; row++) {
            assertAscending(flat.rowEntries, flat.rowOffsets[row], flat.rowOffsets[row + 1],
                    "row " + row);
        }

        for (int column = 0; column < n; column++) {
            assertAscending(flat.columnEntries, flat.columnOffsets[column],
                    flat.columnOffsets[column + 1], "column " + column);
        }
    }

    /**
     * Encoded repair bytes must be byte-identical, which exercises the flat row
     * traversal in the encoder rather than only the stored adjacency.
     */
    @ParameterizedTest(name = "K={0}")
    @MethodSource("cells")
    @DisplayName("encoded repair symbols are byte-identical to the reference graph")
    void encodedRepairMatchesReference(Cell cell) {
        if (cell.r == 0 || (long) (cell.k + cell.r) * 32 > 4_000_000L) {
            return; // Keep the payload work bounded; adjacency parity is asserted above.
        }

        int symbolLength = 32;
        Random random = new Random(cell.seed);
        byte[][] flatSymbols = new byte[cell.k + cell.r][];
        byte[][] referenceSymbols = new byte[cell.k + cell.r][];
        for (int i = 0; i < cell.k; i++) {
            byte[] payload = new byte[symbolLength];
            random.nextBytes(payload);
            flatSymbols[i] = payload.clone();
            referenceSymbols[i] = payload.clone();
        }

        LdpcStaircase.of(ParityCheckMatrix.build(cell.k, cell.r, cell.n1, cell.seed))
                .encode(flatSymbols);
        encodeWithReference(ReferenceParityCheckMatrix.build(cell.k, cell.r, cell.n1, cell.seed),
                referenceSymbols, cell.k, cell.r, symbolLength);

        for (int i = cell.k; i < cell.k + cell.r; i++) {
            assertArrayEquals(referenceSymbols[i], flatSymbols[i],
                    "repair symbol " + i + " of " + cell);
        }
    }

    /** The SPEC.md section 5 running-XOR encode, driven from the reference adjacency. */
    private static void encodeWithReference(ReferenceParityCheckMatrix reference, byte[][] symbols,
            int k, int r, int symbolLength) {
        byte[] previous = null;
        for (int p = 0; p < r; p++) {
            byte[] repair = new byte[symbolLength];
            for (int c : reference.rowColumns[p]) {
                if (c < k) {
                    Symbols.xorInto(repair, symbols[c]);
                }
            }

            if (previous != null) {
                Symbols.xorInto(repair, previous);
            }

            symbols[k + p] = repair;
            previous = repair;
        }
    }

    private static void assertAscending(int[] entries, int from, int to, String what) {
        for (int idx = from + 1; idx < to; idx++) {
            if (entries[idx - 1] >= entries[idx]) {
                throw new AssertionError(
                        what + " is not strictly ascending at flat index " + idx);
            }
        }
    }

    static final class Cell {
        final int k;
        final int r;
        final int n1;
        final int seed;

        Cell(int k, int r, int n1, int seed) {
            this.k = k;
            this.r = r;
            this.n1 = n1;
            this.seed = seed;
        }

        @Override
        public String toString() {
            return "K=" + k + ", R=" + r + ", N1=" + n1 + ", seed=" + seed;
        }
    }
}
