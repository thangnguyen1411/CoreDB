package com.coredb.record;

/**
 * Physical address of a tuple: which page it lives on and which slot within that page.
 * Equivalent to PostgreSQL's ItemPointer (ctid).
 */
public record RecordId(int pageId, int slotNo) {

    @Override
    public String toString() {
        return pageId + ":" + slotNo;
    }

    public static RecordId parse(String s) {
        String[] parts = s.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("invalid RecordId: " + s);
        }
        return new RecordId(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }
}
