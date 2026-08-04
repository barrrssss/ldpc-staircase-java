package io.github.barrrssss.ldpcstaircase;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Byte-array helpers for the GF(2) symbol arithmetic. Addition, subtraction, and the
 * whole code's arithmetic are bitwise XOR, so these three operations are all the
 * codec needs on symbol payloads.
 */
final class Symbols {

    /**
     * A {@code long}-wide view over {@code byte[]}, used to XOR eight bytes per
     * instruction. Native byte order is safe here precisely because XOR is
     * byte-order agnostic: whatever permutation the view applies, it applies
     * identically to both operands and to the result, so the bytes written are the
     * same on every platform.
     */
    private static final VarHandle LONG_VIEW =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());

    private Symbols() {
        // Static utility class; not instantiable.
    }

    /** {@code dst ^= src}. Both arrays must have the same length. */
    static void xorInto(byte[] dst, byte[] src) {
        final int length = dst.length;
        int i = 0;
        int limit = length - 7;
        for (; i < limit; i += 8) {
            LONG_VIEW.set(dst, i, (long) LONG_VIEW.get(dst, i) ^ (long) LONG_VIEW.get(src, i));
        }

        for (; i < length; i++) {
            dst[i] ^= src[i];
        }
    }

    /** {@code a ^= b} over a bitset word array. Both must have the same length. */
    static void xorInto(long[] a, long[] b) {
        for (int i = 0; i < a.length; i++) {
            a[i] ^= b[i];
        }
    }

    /**
     * Copies a byte symbol into packed words for a long-lived XOR-heavy operation.
     *
     * <p>The explicit byte packing is deliberately outside the residual solver's hot
     * elimination loop. It lets that loop operate on ordinary {@code long[]} arrays,
     * which ART can compile as direct array loads and stores instead of dispatching a
     * byte-array {@link VarHandle} access for every word of every row operation.
     */
    static long[] toLongWords(byte[] data) {
        long[] words = new long[(data.length + Long.BYTES - 1) / Long.BYTES];
        for (int i = 0; i < data.length; i++) {
            words[i >>> 3] |= (data[i] & 0xffL) << ((i & 7) << 3);
        }

        return words;
    }

    /** Copies the first {@code length} bytes of packed words back into a symbol. */
    static byte[] fromLongWords(long[] words, int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (words[i >>> 3] >>> ((i & 7) << 3));
        }

        return data;
    }

    /** True when every byte is zero — the parity-check condition for one equation. */
    static boolean isZero(byte[] data) {
        final int length = data.length;
        int i = 0;
        int limit = length - 7;
        for (; i < limit; i += 8) {
            if ((long) LONG_VIEW.get(data, i) != 0L) {
                return false;
            }
        }

        for (; i < length; i++) {
            if (data[i] != 0) {
                return false;
            }
        }

        return true;
    }
}
