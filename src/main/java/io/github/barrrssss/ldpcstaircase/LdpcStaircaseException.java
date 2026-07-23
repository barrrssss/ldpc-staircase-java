package io.github.barrrssss.ldpcstaircase;

/**
 * Thrown for invalid parameters or malformed input to the {@link LdpcStaircase}
 * codec: bad {@code K}/{@code R}/{@code N1}/{@code seed} values, mismatched or
 * zero-length symbols, or a structurally invalid symbol array.
 *
 * <p>A <em>failed decode</em> is not an exception. LDPC-Staircase recovery is
 * probabilistic (SPEC.md section 6.3), so a received set that does not determine the
 * source returns a {@link DecodeResult} with {@link DecodeResult#isComplete()} false
 * rather than throwing.
 */
public final class LdpcStaircaseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LdpcStaircaseException(String message) {
        super(message);
    }

    public LdpcStaircaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
