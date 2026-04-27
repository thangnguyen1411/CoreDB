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
 *   <li>Timing information</li>
 * </ul>
 *
 * <p>This matches PostgreSQL's recovery statistics reporting in
 * startup logs.</p>
 */
public final class RecoveryStats {

    private final long startLsn;
    private final long endLsn;
    private final int redone;
    private final int fpwRestored;
    private final int skippedByPdLsn;
    private final Instant ranAt;
    private final long elapsedMillis;
    private final String noRecoveryReason;

    public RecoveryStats(long startLsn, long endLsn, int redone, int fpwRestored, int skippedByPdLsn,
                         Instant ranAt, long elapsedMillis) {
        this(startLsn, endLsn, redone, fpwRestored, skippedByPdLsn, ranAt, elapsedMillis, null);
    }

    private RecoveryStats(long startLsn, long endLsn, int redone, int fpwRestored, int skippedByPdLsn,
                          Instant ranAt, long elapsedMillis, String noRecoveryReason) {
        this.startLsn = startLsn;
        this.endLsn = endLsn;
        this.redone = redone;
        this.fpwRestored = fpwRestored;
        this.skippedByPdLsn = skippedByPdLsn;
        this.ranAt = ranAt;
        this.elapsedMillis = elapsedMillis;
        this.noRecoveryReason = noRecoveryReason;
    }

    public long startLsn() { return startLsn; }
    public long endLsn() { return endLsn; }
    public int redone() { return redone; }
    public int fpwRestored() { return fpwRestored; }
    public int skippedByPdLsn() { return skippedByPdLsn; }
    public Instant ranAt() { return ranAt; }
    public long elapsedMillis() { return elapsedMillis; }

    /**
     * Creates stats indicating no recovery was needed (clean shutdown or fresh bootstrap).
     */
    public static RecoveryStats noRecoveryNeeded(String reason) {
        return new RecoveryStats(0, 0, 0, 0, 0, Instant.now(), 0, reason);
    }

    /**
     * Returns true if this represents a "no recovery needed" scenario.
     */
    public boolean isNoRecovery() {
        return noRecoveryReason != null;
    }

    /**
     * Returns the reason why no recovery was needed, or null if recovery ran.
     */
    public String noRecoveryReason() {
        return noRecoveryReason;
    }

    /**
     * Total number of WAL records processed.
     */
    public int totalRecords() {
        return redone + fpwRestored + skippedByPdLsn;
    }

    /**
     * Formats the recovery statistics for display.
     */
    public String format() {
        if (isNoRecovery()) {
            return "no recovery on last open (" + noRecoveryReason + ")";
        }
        return String.format(
            "last-recovery: replayed-from=lsn=%d  to=lsn=%d%n" +
            "   redone=%d  fpw-restored=%d  skipped-by-pd_lsn=%d%n" +
            "   ran-at=%s  elapsed=%dms",
            startLsn, endLsn,
            redone, fpwRestored, skippedByPdLsn,
            ranAt, elapsedMillis
        );
    }

    @Override
    public String toString() {
        return format();
    }
}
