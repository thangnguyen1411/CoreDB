package com.coredb.txn;

/**
 * Identifies a page-granularity predicate lock target for SIREAD tracking.
 *
 * <p>Both heap pages and B-tree index pages are eligible — a range scan that
 * visits an index leaf page and then fetches heap rows acquires SIREAD locks
 * for each. The {@code tableOid} distinguishes heap vs. index files (index
 * files carry their own OID, distinct from the heap OID).</p>
 */
public record PredicateLockTag(int tableOid, int pageId) {}
