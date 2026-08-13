package io.github.barrrssss.ldpcstaircase;

/**
 * Test-only rank oracle for erasure-pattern research.
 *
 * <p>When at least {@code K} symbols are received, at most {@code R} columns of the
 * {@code R x (K + R)} parity-check matrix are erased. The erased symbols are uniquely
 * recoverable exactly when those erased columns are linearly independent over GF(2).
 * Payload bytes and right-hand-side row operations therefore do not affect the answer.
 *
 * <p>This oracle incrementally builds a column-echelon basis and stops at the first
 * dependent erased column. It is intentionally confined to tests: production callers
 * must use {@link LdpcStaircase#decode(byte[][], boolean[])} and its completion result.
 */
final class StructuralRecovery {

    private StructuralRecovery() {
    }

    /** Returns whether every symbol marked in {@code erased} is uniquely recoverable. */
    static boolean isRecoverable(ParityCheckMatrix matrix, boolean[] erased) {
        int n = matrix.encodingSymbolCount();
        int r = matrix.repairSymbolCount();
        if (erased.length != n) {
            throw new IllegalArgumentException("erased length must equal K + R");
        }

        Basis basis = new Basis(r);
        int erasedCount = 0;
        for (int column = 0; column < n; column++) {
            if (!erased[column]) {
                continue;
            }

            erasedCount++;
            if (erasedCount > r || !basis.add(matrix.columnEntries, matrix.columnOffsets[column], matrix.columnOffsets[column + 1])) {
                return false;
            }
        }

        return true;
    }

    /**
     * Returns the recovery margin {@code D} for one random removal order.
     *
     * <p>{@code removalOrder[0..R)} is the nested sequence of symbols erased as the
     * received count falls toward {@code K}. Every prefix is uniformly distributed
     * when the caller supplies a shuffled encoding-symbol sequence. Independence is
     * hereditary, so the first dependent column identifies the exact threshold in one
     * rank pass rather than repeated full decoder calls.
     */
    static int margin(ParityCheckMatrix matrix, int[] removalOrder) {
        int r = matrix.repairSymbolCount();
        int n = matrix.encodingSymbolCount();
        if (removalOrder.length < r) {
            throw new IllegalArgumentException("removalOrder must contain at least R symbols");
        }

        Basis basis = new Basis(r);
        boolean[] seen = new boolean[n];
        for (int removed = 0; removed < r; removed++) {
            int column = removalOrder[removed];
            if (column < 0 || column >= n || seen[column]) {
                throw new IllegalArgumentException("removalOrder must contain unique valid indices");
            }
            seen[column] = true;

            if (!basis.add(matrix.columnEntries, matrix.columnOffsets[column], matrix.columnOffsets[column + 1])) {
                return r - removed;
            }
        }

        return 0;
    }

    /**
     * Returns the recovery margin when symbols disappear in indivisible physical
     * failure domains (for example one whole printed page or one damaged page band).
     *
     * <p>{@code domainStarts} contains the inclusive start offset of every domain in
     * {@code removalOrder}, beginning with zero. If any column in a domain makes the
     * erased set dependent, the last physically attainable successful state is the
     * state before that entire domain disappeared. The returned margin therefore
     * rounds conservatively to the domain boundary instead of pretending that part
     * of a lost page remains available.
     */
    static int marginAtDomainBoundaries(ParityCheckMatrix matrix, int[] removalOrder,
            int[] domainStarts) {
        int r = matrix.repairSymbolCount();
        int n = matrix.encodingSymbolCount();
        if (removalOrder.length != n) {
            throw new IllegalArgumentException("removalOrder must contain every symbol");
        }
        if (domainStarts.length == 0 || domainStarts[0] != 0) {
            throw new IllegalArgumentException("domainStarts must begin with zero");
        }

        Basis basis = new Basis(r);
        boolean[] seen = new boolean[n];
        int domain = 0;
        int domainStart = 0;
        for (int removed = 0; removed < r; removed++) {
            while (domain + 1 < domainStarts.length && removed >= domainStarts[domain + 1]) {
                domain++;
                domainStart = domainStarts[domain];
            }

            int column = removalOrder[removed];
            if (column < 0 || column >= n || seen[column]) {
                throw new IllegalArgumentException("removalOrder must contain unique valid indices");
            }
            seen[column] = true;
            if (!basis.add(matrix.columnEntries, matrix.columnOffsets[column], matrix.columnOffsets[column + 1])) {
                return r - domainStart;
            }
        }

        return 0;
    }

    /**
     * Returns the margin when a caller also refuses residual systems wider than
     * {@code maxResidualUnknowns}. This models
     * {@link DecodeOptions#withMaxResidualUnknowns(int)} without payload work.
     */
    static int margin(ParityCheckMatrix matrix, int[] removalOrder, int maxResidualUnknowns) {
        if (maxResidualUnknowns < 0) {
            throw new IllegalArgumentException("maxResidualUnknowns must be non-negative");
        }
        int rankMargin = margin(matrix, removalOrder);
        if (maxResidualUnknowns == Integer.MAX_VALUE) {
            return rankMargin;
        }

        int r = matrix.repairSymbolCount();
        int largestIndependentPrefix = r - rankMargin;
        int low = 0;
        int high = largestIndependentPrefix;
        while (low < high) {
            int removed = (low + high + 1) >>> 1;
            boolean[] erased = erasedPrefix(matrix.encodingSymbolCount(), removalOrder, removed);
            if (residualUnknownCount(matrix, erased) <= maxResidualUnknowns) {
                low = removed;
            } else {
                high = removed - 1;
            }
        }
        return r - low;
    }

    /** Number of variables left when degree-one peeling reaches its fixed point. */
    static int residualUnknownCount(ParityCheckMatrix matrix, boolean[] erased) {
        int n = matrix.encodingSymbolCount();
        int r = matrix.repairSymbolCount();
        if (erased.length != n) {
            throw new IllegalArgumentException("erased length must equal K + R");
        }

        boolean[] unknownSymbol = erased.clone();
        int remaining = 0;
        for (boolean unknown : unknownSymbol) {
            if (unknown) {
                remaining++;
            }
        }

        int[] unknownInRow = new int[r];
        int[] stack = new int[Math.max(1, r)];
        int top = 0;
        for (int row = 0; row < r; row++) {
            int unknown = 0;
            for (int idx = matrix.rowOffsets[row]; idx < matrix.rowOffsets[row + 1]; idx++) {
                if (unknownSymbol[matrix.rowEntries[idx]]) {
                    unknown++;
                }
            }
            unknownInRow[row] = unknown;
            if (unknown == 1) {
                stack[top++] = row;
            }
        }

        while (top > 0) {
            int row = stack[--top];
            if (unknownInRow[row] != 1) {
                continue;
            }

            int recovered = -1;
            for (int idx = matrix.rowOffsets[row]; idx < matrix.rowOffsets[row + 1]; idx++) {
                int column = matrix.rowEntries[idx];
                if (unknownSymbol[column]) {
                    recovered = column;
                    break;
                }
            }
            if (recovered < 0) {
                continue;
            }

            unknownSymbol[recovered] = false;
            remaining--;
            for (int idx = matrix.columnOffsets[recovered]; idx < matrix.columnOffsets[recovered + 1]; idx++) {
                int affectedRow = matrix.columnEntries[idx];
                int left = --unknownInRow[affectedRow];
                if (left == 1) {
                    stack[top++] = affectedRow;
                }
            }
        }

        return remaining;
    }

    private static boolean[] erasedPrefix(int n, int[] removalOrder, int removed) {
        boolean[] erased = new boolean[n];
        for (int index = 0; index < removed; index++) {
            erased[removalOrder[index]] = true;
        }
        return erased;
    }

    private static final class Basis {
        private final long[][] vectorByPivot;
        private final int words;

        Basis(int dimensions) {
            vectorByPivot = new long[dimensions][];
            words = (dimensions + 63) >>> 6;
        }

        /** Adds the column whose rows are {@code entries[from, to)} of a flat adjacency array. */
        boolean add(int[] entries, int from, int to) {
            if (words == 0) {
                return false;
            }

            long[] vector = new long[words];
            int pivot = -1;
            for (int idx = from; idx < to; idx++) {
                int row = entries[idx];
                vector[row >>> 6] ^= 1L << (row & 63);
                if (row > pivot) {
                    pivot = row;
                }
            }

            while (pivot >= 0) {
                long[] prior = vectorByPivot[pivot];
                if (prior == null) {
                    vectorByPivot[pivot] = vector;
                    return true;
                }

                int lastWord = pivot >>> 6;
                for (int word = 0; word <= lastWord; word++) {
                    vector[word] ^= prior[word];
                }
                pivot = highestSetBit(vector, lastWord);
            }

            return false;
        }

        private static int highestSetBit(long[] vector, int fromWord) {
            for (int word = fromWord; word >= 0; word--) {
                long value = vector[word];
                if (value != 0) {
                    return (word << 6) + 63 - Long.numberOfLeadingZeros(value);
                }
            }
            return -1;
        }
    }
}
