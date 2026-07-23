package io.github.barrrssss.ldpcstaircase;

/**
 * Optional callback invoked during {@link LdpcStaircase#decode} so a caller (for
 * example a UI) can render a real progress bar instead of an indeterminate spinner.
 *
 * <p>{@code completedUnits} rises monotonically from {@code 0} toward
 * {@code totalUnits} over one decode call. Both are abstract "work units" — an
 * internal estimate of the decode's cost, spanning the peeling pass and, when it is
 * needed, the residual solve — so treat the ratio {@code completedUnits / totalUnits}
 * as the fraction complete and do not ascribe an absolute unit to the numbers.
 * {@code totalUnits} is constant within a single call and always {@code > 0} when the
 * callback fires; the final invocation reports {@code completedUnits == totalUnits}.
 *
 * <p>The estimate reserves room for a residual solve up front, because whether one is
 * needed is only known after peeling reaches its fixed point. A decode that peels
 * cleanly therefore jumps from the peeling fraction straight to complete — the common
 * and fastest case.
 *
 * <p>The callback runs inline on the calling thread, so keep it cheap (post to a UI
 * thread rather than doing work here). To <b>cancel</b> a long decode, throw an
 * unchecked exception from {@link #onProgress}: it propagates out of the decode call.
 * The symbol array may be left partially recovered in that case and should be
 * discarded.
 */
@FunctionalInterface
public interface ProgressListener {

    /**
     * Reports incremental decode progress.
     *
     * @param completedUnits work completed so far, in {@code [0, totalUnits]}, monotonic
     *     within one decode call
     * @param totalUnits total work for this decode call; constant per call, {@code > 0}
     */
    void onProgress(long completedUnits, long totalUnits);
}
