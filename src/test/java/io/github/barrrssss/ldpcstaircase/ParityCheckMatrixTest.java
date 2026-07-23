package io.github.barrrssss.ldpcstaircase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Structural and determinism properties of {@link ParityCheckMatrix} (SPEC.md section 4). */
class ParityCheckMatrixTest {

    @ParameterizedTest(name = "K={0} R={1} N1={2} seed={3}")
    @CsvSource({
            "16, 8, 3, 1",
            "64, 32, 7, 42",
            "100, 50, 7, 2147483646",
            "37, 11, 5, 999",
            "250, 25, 7, 123456",
    })
    void structureFollowsTheSpec(int k, int r, int n1, int seed) {
        ParityCheckMatrix h = ParityCheckMatrix.build(k, r, n1, seed);

        assertEquals(k, h.sourceSymbolCount());
        assertEquals(r, h.repairSymbolCount());
        assertEquals(k + r, h.encodingSymbolCount());
        assertEquals(n1, h.leftDegree());
        assertEquals(seed, h.seed());

        // Section 4.2: every source column carries at least N1 ones (section 4.3 may
        // add more when it repairs a low-degree row).
        for (int j = 0; j < k; j++) {
            assertTrue(h.columnDegree(j) >= n1,
                    "source column " + j + " has degree " + h.columnDegree(j) + " < N1=" + n1);
        }

        // Section 4.4: repair column K+p appears in equations p and p+1, so every repair
        // column has degree 2 except the last, which has only its own equation.
        for (int p = 0; p < r; p++) {
            int expected = (p == r - 1) ? 1 : 2;
            assertEquals(expected, h.columnDegree(k + p),
                    "repair column " + p + " has the wrong degree");
        }

        // Section 4.3 plus the staircase: no equation may be trivially weak.
        for (int i = 0; i < r; i++) {
            assertTrue(h.rowDegree(i) >= 3,
                    "row " + i + " has degree " + h.rowDegree(i) + "; expected >= 2 left + staircase");
        }

        // Indices are ascending and duplicate-free in both directions.
        for (int i = 0; i < r; i++) {
            assertAscendingAndUnique(h.rowColumns(i), "row " + i);
        }
        for (int c = 0; c < k + r; c++) {
            assertAscendingAndUnique(h.columnRows(c), "column " + c);
        }

        // The row view and the column view describe the same set of ones.
        assertEquals(entriesByRow(h), entriesByColumn(h));
        assertEquals(entriesByRow(h).size(), h.entryCount());
    }

    /**
     * The staircase itself: row 0 holds only repair symbol 0, and row i additionally
     * holds repair symbol i-1. This is what makes encoding a running XOR.
     */
    @Test
    void staircaseIsExact() {
        int k = 20;
        int r = 10;
        ParityCheckMatrix h = ParityCheckMatrix.build(k, r, 3, 5);

        assertArrayEquals(new int[] {k}, repairColumnsOf(h, 0),
                "row 0 must hold repair symbol 0 and no other repair symbol");
        for (int i = 1; i < r; i++) {
            assertArrayEquals(new int[] {k + i - 1, k + i}, repairColumnsOf(h, i),
                    "row " + i + " must hold exactly repair symbols " + (i - 1) + " and " + i);
        }

        for (int i = 1; i < r; i++) {
            assertTrue(contains(h.rowColumns(i), k + i), "row " + i + " must contain repair " + i);
            assertTrue(contains(h.rowColumns(i), k + i - 1),
                    "row " + i + " must contain repair " + (i - 1));
        }
    }

    @Test
    void sameParametersGiveTheSameMatrix() {
        ParityCheckMatrix a = ParityCheckMatrix.build(64, 32, 7, 42);
        ParityCheckMatrix b = ParityCheckMatrix.build(64, 32, 7, 42);
        assertArrayEquals(a.serialize(), b.serialize());
    }

    @Test
    void differentSeedsGiveDifferentMatrices() {
        ParityCheckMatrix a = ParityCheckMatrix.build(64, 32, 7, 42);
        ParityCheckMatrix b = ParityCheckMatrix.build(64, 32, 7, 43);
        assertNotEquals(hex(a.serialize()), hex(b.serialize()));
    }

    @Test
    void noRepairYieldsAnEmptyMatrix() {
        ParityCheckMatrix h = ParityCheckMatrix.build(8, 0, 7, 1);
        assertEquals(0, h.repairSymbolCount());
        assertEquals(0, h.entryCount());
        assertEquals(8, h.encodingSymbolCount());
        for (int c = 0; c < 8; c++) {
            assertEquals(0, h.columnDegree(c));
        }
    }

    /**
     * The degenerate {@code K = 1} rule of SPEC.md section 4.3: with only one source
     * column, rows can be raised to degree 1 and no further.
     */
    @Test
    void singleSourceColumnIsHandled() {
        ParityCheckMatrix h = ParityCheckMatrix.build(1, 1, 1, 7);
        assertEquals(1, h.columnDegree(0));
        assertEquals(2, h.rowDegree(0)); // the source symbol plus repair symbol 0
    }

    @Test
    void serializationMatchesTheDeclaredLayout() {
        ParityCheckMatrix h = ParityCheckMatrix.build(16, 8, 3, 1);
        byte[] bytes = h.serialize();

        assertEquals(4 * (2 + h.repairSymbolCount() + h.entryCount()), bytes.length);
        assertEquals(h.repairSymbolCount(), readU32(bytes, 0));
        assertEquals(h.encodingSymbolCount(), readU32(bytes, 4));

        int offset = 8;
        for (int i = 0; i < h.repairSymbolCount(); i++) {
            int degree = readU32(bytes, offset);
            offset += 4;
            assertEquals(h.rowDegree(i), degree);
            int[] columns = h.rowColumns(i);
            for (int idx = 0; idx < degree; idx++) {
                assertEquals(columns[idx], readU32(bytes, offset));
                offset += 4;
            }
        }

        assertEquals(bytes.length, offset);
    }

    @Test
    void rejectsInvalidParameters() {
        assertThrows(LdpcStaircaseException.class, () -> ParityCheckMatrix.build(0, 8, 3, 1));
        assertThrows(LdpcStaircaseException.class, () -> ParityCheckMatrix.build(16, -1, 3, 1));
        assertThrows(LdpcStaircaseException.class, () -> ParityCheckMatrix.build(16, 8, 0, 1));
        assertThrows(LdpcStaircaseException.class, () -> ParityCheckMatrix.build(16, 8, 9, 1),
                "N1 > R cannot terminate");
        assertThrows(LdpcStaircaseException.class, () -> ParityCheckMatrix.build(16, 8, 3, 0));
        assertThrows(LdpcStaircaseException.class,
                () -> ParityCheckMatrix.build(16, 8, 3, Integer.MAX_VALUE));
    }

    /** Accessors must hand out copies so a caller cannot corrupt a shared matrix. */
    @Test
    void accessorsReturnCopies() {
        ParityCheckMatrix h = ParityCheckMatrix.build(16, 8, 3, 1);
        int[] row = h.rowColumns(0);
        row[0] = -12345;
        assertNotEquals(-12345, h.rowColumns(0)[0]);

        int[] column = h.columnRows(0);
        column[0] = -12345;
        assertNotEquals(-12345, h.columnRows(0)[0]);
    }

    private static void assertAscendingAndUnique(int[] values, String label) {
        for (int i = 1; i < values.length; i++) {
            assertTrue(values[i] > values[i - 1],
                    label + " is not strictly ascending at index " + i);
        }
    }

    private static boolean contains(int[] values, int value) {
        for (int v : values) {
            if (v == value) {
                return true;
            }
        }

        return false;
    }

    /** The repair-part columns of {@code row}, i.e. its slice of the staircase. */
    private static int[] repairColumnsOf(ParityCheckMatrix h, int row) {
        int k = h.sourceSymbolCount();
        return java.util.Arrays.stream(h.rowColumns(row)).filter(c -> c >= k).toArray();
    }

    private static List<String> entriesByRow(ParityCheckMatrix h) {
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < h.repairSymbolCount(); i++) {
            for (int c : h.rowColumns(i)) {
                entries.add(i + ":" + c);
            }
        }

        entries.sort(null);
        return entries;
    }

    private static List<String> entriesByColumn(ParityCheckMatrix h) {
        List<String> entries = new ArrayList<>();
        for (int c = 0; c < h.encodingSymbolCount(); c++) {
            for (int i : h.columnRows(c)) {
                entries.add(i + ":" + c);
            }
        }

        entries.sort(null);
        return entries;
    }

    private static int readU32(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b & 0xFF));
        }

        return sb.toString();
    }
}
