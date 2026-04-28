package com.coredb.recovery;

import java.time.Instant;

/**
 * Statistics from a recovery operation.
 *
 * <p>Records the outcome of a redo-only recovery pass, including:
 * <ul>
 *   <li>LSN range that was replayed</li>
 *   <li>Count of records redone</li>
 *   <li>Count of full-page writes restored</li>
 *   <li>Count of records skipped by pd_lsn check (already applied)</li>
 *   <li>Count of XACT_COMMIT / XACT_ABORT records replayed into clog</li>
 *   <li>Count of in-progress XIDs swept to ABORTED at startup</li>
 *   <li>Timing information</li>
 * </ul>
 */
public final class RecoveryStats {

    private final long startLsn;
    private final long endLsn;
    private final int redone;
    private final int fpwRestored;
    private final int skippedByPdLsn;
    private final int xactCommitReplayed;
    private final int xactAbortReplayed;
    private final int xidsSweptToAborted;
    private final Instant ranAt;
    private final long elapsedMillis;
    private final String noRecoveryReason;

    public RecoveryStats(long startLsn, long endLsn, int redone, int fpwRestored, int skippedByPdLsn,
                         Instant ranAt, long elapsedMillis) {
        this(startLsn, endLsn, redone, fpwRestored, skippedByPdLsn, 0, 0, 0, ranAt, elapsedMillis, null);
    }

    public RecoveryStats(long startLsn, long endLsn, int redone, int fpwRestored, int skippedByPdLsn,
                         int xactCommitReplayed, int xactAbortReplayed, int xidsSweptToAborted,
                         Instant ranAt, long elapsedMillis) {
        this(startLsn, endLsn, redone, fpwRestored, skippedByPdLsn,
             xactCommitReplayed, xactAbortReplayed, xidsSweptToAborted,
             ranAt, elapsedMillis, null);
    }

    private RecoveryStats(long startLsn, long endLsn, int redone, int fpwRestored, int skippedByPdLsn,
                          int xactCommitReplayed, int xactAbortReplayed, int xidsSweptToAborted,
                          Instant ranAt, long elapsedMillis, String noRecoveryReason) {
        this.startLsn = startLsn;
        this.endLsn = endLsn;
        this.redone = redone;
        this.fpwRestored = fpwRestored;
        this.skippedByPdLsn = skippedByPdLsn;
        this.xactCommitReplayed = xactCommitReplayed;
        this.xactAbortReplayed = xactAbortReplayed;
        this.xidsSweptToAborted = xidsSweptToAborted;
        this.ranAt = ranAt;
        this.elapsedMillis = elapsedMillis;
        this.noRecoveryReason = noRecoveryReason;
    }

    public long startLsn() { return startLsn; }
    public long endLsn() { return endLsn; }
    public int redone() { return redone; }
    public int fpwRestored() { return fpwRestored; }
    public int skippedByPdLsn() { return skippedByPdLsn; }
    public int xactCommitReplayed() { return xactCommitReplayed; }
    public int xactAbortReplayed() { return xactAbortReplayed; }
    public int xidsSweptToAborted() { return xidsSweptToAborted; }
    public Instant ranAt() { return ranAt; }
    public long elapsedMillis() { return elapsedMillis; }

    public static RecoveryStats noRecoveryNeeded(String reason) {
        return new RecoveryStats(0, 0, 0, 0, 0, 0, 0, 0, Instant.now(), 0, reason);
    }

    public boolean isNoRecovery() {
        return noRecoveryReason != null;
    }

    public String noRecoveryReason() {
        return noRecoveryReason;
    }

    public int totalRecords() {
        return redone + fpwRestored + skippedByPdLsn + xactCommitReplayed + xactAbortReplayed;
    }

    public String format() {
        if (isNoRecovery()) {
            return "no recovery on last open (" + noRecoveryReason + ")";
        }
        return String.format(
            "last-recovery: replayed-from=lsn=%d  to=lsn=%d%n" +
            "   redone=%d  fpw-restored=%d  skipped-by-pd_lsn=%d%n" +
            "   xact-commit-replayed=%d  xact-abort-replayed=%d  xids-swept-to-aborted=%d%n" +
            "   ran-at=%s  elapsed=%dms",
            startLsn, endLsn,
            redone, fpwRestored, skippedByPdLsn,
            xactCommitReplayed, xactAbortReplayed, xidsSweptToAborted,
            ranAt, elapsedMillis
        );
    }

    @Override
    public String toString() {
        return format();
    }
}
