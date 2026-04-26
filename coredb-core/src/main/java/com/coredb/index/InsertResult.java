package com.coredb.index;

/**
 * Result of an insert operation on a B-tree page.
 */
public enum InsertResult {
    /**
     * Insert was successful.
     */
    OK,

    /**
     * Insert failed because the page is full.
     * The caller should handle this by splitting the page.
     */
    FULL,

    /**
     * Insert failed because the key already exists.
     * For primary key indexes, this is a contract violation — the upper
     * layer should guarantee uniqueness via the catalog.
     */
    DUPLICATE_KEY
}
