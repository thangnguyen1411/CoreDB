package com.coredb.vacuum;

/**
 * Aggregate statistics from a single VACUUM run.
 *
 * @param pagesScanned        total heap pages examined
 * @param deadTuples          dead tuple slots removed across all pages
 * @param indexEntriesRemoved index entries deleted to match the removed heap slots
 * @param bytesReclaimed      bytes freed in heap pages
 * @param pagesEmptied        heap pages that are now completely empty after compaction
 */
public record VacuumStats(
    int pagesScanned,
    int deadTuples,
    int indexEntriesRemoved,
    long bytesReclaimed,
    int pagesEmptied
) {
    public static final VacuumStats EMPTY = new VacuumStats(0, 0, 0, 0, 0);
}
