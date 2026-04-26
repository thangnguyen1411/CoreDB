package com.coredb.index;

/**
 * Result of a leaf page split operation.
 *
 * <p>When a leaf page splits, it divides its entries between the original (left)
 * page and a newly allocated right sibling. The separator key is the smallest key
 * now living on the right page — this value is promoted to the parent internal
 * page as the routing separator.</p>
 *
 * <p>Invariant: After split, the separator key equals {@code rightPage.keyAt(0)}.
 * All keys in the left page are strictly less than the separator, and all keys
 * in the right page are greater than or equal to the separator.</p>
 *
 * @param separatorKey   the smallest key now on the right page (first key of new right page)
 * @param newRightPageId the page ID of the newly allocated right sibling
 */
public record SplitResult(long separatorKey, int newRightPageId) {
}
