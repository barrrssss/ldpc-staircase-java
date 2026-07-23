package io.github.barrrssss.ldpcstaircase;

/**
 * The outcome of one {@link LdpcStaircase#decode} call.
 *
 * <p>LDPC-Staircase recovery is probabilistic, not MDS: there is no "any {@code K} of
 * {@code N}" guarantee, and a session holding {@code K} or more symbols can still
 * fail. Callers MUST decide completion from {@link #isComplete()} and never from a
 * received-symbol count (SPEC.md section 6.3).
 *
 * <p>The remaining accessors describe how much work the decode needed, which is the
 * data a product uses to measure its recovery margin {@code D} and to size a memory
 * seatbelt.
 */
public final class DecodeResult {

    /** How far through SPEC.md section 6 the decode had to go. */
    public enum Stage {
        /** Nothing was missing; the decode did no work. */
        NONE,

        /** Degree-one peeling was the last stage that ran (section 6.1). */
        PEELING,

        /** The residual GF(2) solve was attempted (section 6.2). */
        RESIDUAL_SOLVE
    }

    private final boolean complete;
    private final Stage stage;
    private final int recoveredSourceCount;
    private final int recoveredSymbolCount;
    private final int[] missingSourceIndices;
    private final boolean residualSolveSkipped;
    private final int residualUnknownCount;
    private final int residualRowCount;
    private final int residualRank;

    DecodeResult(boolean complete, Stage stage, int recoveredSourceCount, int recoveredSymbolCount,
            int[] missingSourceIndices, boolean residualSolveSkipped, int residualUnknownCount,
            int residualRowCount, int residualRank) {
        this.complete = complete;
        this.stage = stage;
        this.recoveredSourceCount = recoveredSourceCount;
        this.recoveredSymbolCount = recoveredSymbolCount;
        this.missingSourceIndices = missingSourceIndices;
        this.residualSolveSkipped = residualSolveSkipped;
        this.residualUnknownCount = residualUnknownCount;
        this.residualRowCount = residualRowCount;
        this.residualRank = residualRank;
    }

    /** True when all {@code K} source symbols are present after the decode. */
    public boolean isComplete() {
        return complete;
    }

    /** The furthest stage this decode reached. */
    public Stage stage() {
        return stage;
    }

    /** How many missing <em>source</em> symbols this call recovered. */
    public int recoveredSourceCount() {
        return recoveredSourceCount;
    }

    /**
     * How many symbols in total this call recovered. Missing repair symbols fall out of
     * the same equations at no extra cost, so they are recovered and written back too.
     */
    public int recoveredSymbolCount() {
        return recoveredSymbolCount;
    }

    /** The source symbol indices still missing, ascending. Empty when complete. */
    public int[] missingSourceIndices() {
        return missingSourceIndices.clone();
    }

    /** True when peeling stalled and the residual solve was attempted. */
    public boolean residualSolveAttempted() {
        return stage == Stage.RESIDUAL_SOLVE;
    }

    /**
     * True when peeling stalled but the residual solve did not run — either it was
     * disabled, or the residual system exceeded
     * {@link DecodeOptions#maxResidualUnknowns()}.
     */
    public boolean residualSolveSkipped() {
        return residualSolveSkipped;
    }

    /**
     * The number of variables still unknown when peeling reached its fixed point, i.e.
     * the width of the residual system. Zero when peeling succeeded.
     */
    public int residualUnknownCount() {
        return residualUnknownCount;
    }

    /** The number of unresolved equations in the residual system. */
    public int residualRowCount() {
        return residualRowCount;
    }

    /**
     * The GF(2) rank of the residual system, or {@code -1} if no solve ran. A rank
     * below {@link #residualUnknownCount()} means the received set left free variables
     * and could not determine every symbol.
     */
    public int residualRank() {
        return residualRank;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("DecodeResult{complete=").append(complete)
                .append(", stage=").append(stage)
                .append(", recoveredSource=").append(recoveredSourceCount)
                .append(", recoveredSymbols=").append(recoveredSymbolCount)
                .append(", missingSource=").append(missingSourceIndices.length);
        if (residualUnknownCount > 0) {
            sb.append(", residual=").append(residualRowCount).append('x').append(residualUnknownCount)
                    .append(", rank=").append(residualRank)
                    .append(", skipped=").append(residualSolveSkipped);
        }

        return sb.append('}').toString();
    }
}
