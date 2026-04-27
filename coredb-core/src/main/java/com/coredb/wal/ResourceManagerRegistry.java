package com.coredb.wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Registry for resource managers.
 *
 * <p>Maintains a mapping from rmgrId to ResourceManager instance and provides
 * dispatch functionality for WAL redo operations.</p>
 *
 * <p>This matches PostgreSQL's RmgrTable, which provides a lookup from
 * resource manager ID to the handler functions.</p>
 */
public final class ResourceManagerRegistry {

    private final Map<Byte, ResourceManager> managers = new HashMap<>();

    /**
     * Creates a registry with the standard resource managers registered.
     *
     * @return a new registry with HEAP, BTREE, and XLOG managers
     */
    public static ResourceManagerRegistry createStandard() {
        ResourceManagerRegistry registry = new ResourceManagerRegistry();
        registry.register(new HeapResourceManager());
        registry.register(new BTreeResourceManager());
        registry.register(new XLogResourceManager());
        return registry;
    }

    /**
     * Creates an empty registry.
     */
    public ResourceManagerRegistry() {
    }

    /**
     * Registers a resource manager.
     *
     * @param manager the manager to register
     * @throws IllegalArgumentException if a manager with the same rmgrId is already registered
     */
    public void register(ResourceManager manager) {
        byte resourceManagerId = manager.getResourceManagerId();
        if (managers.containsKey(resourceManagerId)) {
            throw new IllegalArgumentException(
                "Resource manager with ID=" + resourceManagerId + " already registered");
        }
        managers.put(resourceManagerId, manager);
    }

    /**
     * Looks up a resource manager by ID.
     *
     * @param rmgrId the resource manager ID
     * @return the registered manager, or null if not found
     */
    public ResourceManager get(byte rmgrId) {
        return managers.get(rmgrId);
    }

    /**
     * Dispatches a WAL record to the appropriate resource manager for redo.
     *
     * <p>The caller is responsible for:
     * <ol>
     *   <li>Resolving record.tableOid() and record.pageId() to a file path</li>
     *   <li>Reading the page into targetPage</li>
     *   <li>Calling this dispatch method</li>
     *   <li>Writing the modified page back to disk</li>
     * </ol>
     *
     * @param record the WAL record to replay
     * @param targetPage the page buffer to modify (must be PAGE_SIZE bytes)
     * @throws UnsupportedOperationException if no manager is registered for the record's rmgrId
     * @throws IOException if an I/O error occurs during redo
     */
    public void dispatch(XLogRecord record, ByteBuffer targetPage) throws IOException {
        ResourceManager manager = managers.get(record.resourceManager());
        if (manager == null) {
            throw new UnsupportedOperationException(
                "No resource manager registered for rmgrId=" + record.resourceManager());
        }
        manager.redo(record, targetPage);
    }

    /**
     * Returns true if a manager is registered for the given rmgrId.
     *
     * @param rmgrId the resource manager ID
     * @return true if a manager is registered
     */
    public boolean hasManager(byte rmgrId) {
        return managers.containsKey(rmgrId);
    }

    /**
     * Returns the number of registered managers.
     *
     * @return the count of registered managers
     */
    public int size() {
        return managers.size();
    }
}
