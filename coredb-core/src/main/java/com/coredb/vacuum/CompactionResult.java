package com.coredb.vacuum;

import java.util.List;

/**
 * Result of a single-page compaction pass.
 *
 * @param newPageBytes  the rewritten page buffer (same size as the original)
 * @param deadSlots     slot numbers whose tuples were removed and whose ItemIds
 *                      are now LP_UNUSED; callers use these to drive index cleanup
 * @param reclaimedBytes bytes freed by removing dead tuples
 */
public record CompactionResult(byte[] newPageBytes, List<Integer> deadSlots, int reclaimedBytes) {

    public boolean hasDeadTuples() {
        return !deadSlots.isEmpty();
    }
}
