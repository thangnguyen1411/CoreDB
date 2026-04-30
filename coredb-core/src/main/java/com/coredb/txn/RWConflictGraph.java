package com.coredb.txn;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks rw-antidependency edges between serializable transactions for SSI pivot detection.
 *
 * <p>An edge {@code reader → writer} means: reader read a page that writer subsequently wrote.
 * A transaction is a "dangerous pivot" (Cahill 2008) when it has both an incoming edge
 * ({@code T0 → pivot}) and an outgoing edge ({@code pivot → T2}), and at least one of T0 or T2
 * has already committed — forming the {@code T0 → pivot → T2} dangerous structure.</p>
 *
 * <p>All state is in-memory. On process restart, in-progress transactions are treated as aborted
 * by recovery, so no graph persistence is needed.</p>
 *
 * <p>V1 limitation: SIREAD locks (and therefore graph edges) are released at commit/rollback.
 * This means the classic read-only anomaly where the read-only transaction commits before the
 * pivot forms is not detected. The common write-skew case (both participants in-progress) is
 * always detected.</p>
 */
public final class RWConflictGraph {

    // outEdges[reader] = set of writers that wrote pages the reader read
    private final Map<Integer, Set<Integer>> outEdges = new ConcurrentHashMap<>();
    // inEdges[writer] = set of readers that read pages the writer wrote
    private final Map<Integer, Set<Integer>> inEdges = new ConcurrentHashMap<>();
    // Records commit order for the "at least one committed" check in isDangerousPivot
    private final Map<Integer, Long> commitOrder = new ConcurrentHashMap<>();
    private final AtomicLong commitClock = new AtomicLong(0);

    /**
     * Records that {@code reader} read a page that {@code writer} wrote (or is writing).
     *
     * <p>Adding an edge for xids that are the same is silently ignored.</p>
     */
    public void addEdge(int reader, int writer) {
        if (reader == writer) {
            return;
        }
        outEdges.computeIfAbsent(reader, k -> ConcurrentHashMap.newKeySet()).add(writer);
        inEdges.computeIfAbsent(writer, k -> ConcurrentHashMap.newKeySet()).add(reader);
    }

    /**
     * Marks {@code xid} as committed, recording its position in the commit order.
     *
     * <p>The commit ordering is used by {@link #isDangerousPivot} to determine whether
     * a dangerous structure has actually formed: if neither T0 nor T2 has committed yet,
     * the structure is incomplete and we should not abort yet.</p>
     */
    public void markCommitted(int xid) {
        commitOrder.put(xid, commitClock.incrementAndGet());
    }

    /**
     * Returns true if {@code xid} is a dangerous pivot: it has both an incoming rw-edge
     * (some T0 read a page xid wrote) and an outgoing rw-edge (xid read a page some T2 wrote),
     * with at least one of T0 or T2 already committed.
     *
     * <p>The "at least one committed" check is what prevents false positives under heavy load —
     * a transaction that has both in and out edges but all its neighbours are still in-progress
     * is not yet a confirmed dangerous structure.</p>
     *
     * <p>Note: T0 and T2 may be the same transaction. The two-transaction write-skew pattern
     * (T_A reads X, T_B reads X, T_A writes Y, T_B writes Y) produces a cycle
     * T_A →rw T_B →rw T_A. When checking the pivot at T_A: in={T_B}, out={T_B}. Skipping
     * this pair would cause write-skew to go undetected. The important check is solely whether
     * at least one neighbour has committed, making the structure concrete.</p>
     */
    public boolean isDangerousPivot(int xid) {
        Set<Integer> in = inEdges.getOrDefault(xid, Set.of());
        Set<Integer> out = outEdges.getOrDefault(xid, Set.of());
        if (in.isEmpty() || out.isEmpty()) {
            return false;
        }
        for (int t0 : in) {
            for (int t2 : out) {
                if (commitOrder.containsKey(t0) || commitOrder.containsKey(t2)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Removes all graph state for {@code xid}: its edges as reader and writer, and its
     * commit-order entry. Also purges {@code xid} from neighbour edge sets.
     *
     * <p>Called at commit and rollback.</p>
     */
    public void releaseAll(int xid) {
        Set<Integer> out = outEdges.remove(xid);
        if (out != null) {
            for (int writer : out) {
                Set<Integer> writerIn = inEdges.get(writer);
                if (writerIn != null) {
                    writerIn.remove(xid);
                }
            }
        }
        Set<Integer> in = inEdges.remove(xid);
        if (in != null) {
            for (int reader : in) {
                Set<Integer> readerOut = outEdges.get(reader);
                if (readerOut != null) {
                    readerOut.remove(xid);
                }
            }
        }
        commitOrder.remove(xid);
    }

    /**
     * Returns a snapshot of outgoing edges for diagnostic purposes.
     */
    public Map<Integer, Set<Integer>> outEdges() {
        return Map.copyOf(outEdges);
    }

    /**
     * Returns a snapshot of incoming edges for diagnostic purposes.
     */
    public Map<Integer, Set<Integer>> inEdges() {
        return Map.copyOf(inEdges);
    }
}
