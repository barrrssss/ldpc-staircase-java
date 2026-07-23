package io.github.barrrssss.ldpcstaircase;

/**
 * Immutable knobs for {@link LdpcStaircase#decode(byte[][], boolean[], DecodeOptions)}.
 * Start from {@link #defaults()} and derive with the {@code with*} methods.
 *
 * <p>None of these change the byte-level contract of a decode that runs to completion;
 * they bound or instrument it.
 */
public final class DecodeOptions {

    private static final DecodeOptions DEFAULTS = new DecodeOptions(true, Integer.MAX_VALUE, null);

    private final boolean residualSolveEnabled;
    private final int maxResidualUnknowns;
    private final ProgressListener progressListener;

    private DecodeOptions(boolean residualSolveEnabled, int maxResidualUnknowns,
            ProgressListener progressListener) {
        this.residualSolveEnabled = residualSolveEnabled;
        this.maxResidualUnknowns = maxResidualUnknowns;
        this.progressListener = progressListener;
    }

    /**
     * Residual solve enabled, no bound on the residual system, no progress listener.
     */
    public static DecodeOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Enables or disables the stage-2 residual solve (SPEC.md section 6.2). Disabling
     * it leaves a peeling-only decoder: much cheaper, but it strands recoverable
     * sessions whenever the sparse graph stalls, so it is not a production setting.
     * It exists to isolate the two stages in tests and benchmarks.
     */
    public DecodeOptions withResidualSolve(boolean enabled) {
        return new DecodeOptions(enabled, maxResidualUnknowns, progressListener);
    }

    /**
     * Refuses to start the residual solve when more than {@code max} variables remain
     * unknown after peeling, reporting the decode as failed instead
     * ({@link DecodeResult#residualSolveSkipped()}).
     *
     * <p>This is the seam through which a product enforces a memory seatbelt: the
     * solve's working set grows as {@code |M| * |U| / 8} bytes of coefficients plus one
     * symbol-sized right-hand side per unresolved row, and its time grows faster still.
     * Callers that must not exceed a heap budget should set this rather than discover
     * the limit as an {@code OutOfMemoryError}.
     *
     * @throws LdpcStaircaseException if {@code max} is negative.
     */
    public DecodeOptions withMaxResidualUnknowns(int max) {
        if (max < 0) {
            throw new LdpcStaircaseException("maxResidualUnknowns must be >= 0; got " + max + ".");
        }

        return new DecodeOptions(residualSolveEnabled, max, progressListener);
    }

    /** Attaches (or with {@code null}, clears) a decode progress callback. */
    public DecodeOptions withProgressListener(ProgressListener listener) {
        return new DecodeOptions(residualSolveEnabled, maxResidualUnknowns, listener);
    }

    /** Whether the stage-2 residual solve may run. */
    public boolean residualSolveEnabled() {
        return residualSolveEnabled;
    }

    /** The residual-system size above which the solve is refused. */
    public int maxResidualUnknowns() {
        return maxResidualUnknowns;
    }

    /** The attached progress callback, or {@code null}. */
    public ProgressListener progressListener() {
        return progressListener;
    }
}
