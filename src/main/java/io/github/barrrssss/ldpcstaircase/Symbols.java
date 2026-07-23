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
