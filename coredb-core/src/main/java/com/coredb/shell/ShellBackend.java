package com.coredb.shell;

/**
 * Abstraction over the database back-end used by the shell.
 * Currently, we provides LocalShellBackend (calls CoreDB directly).
 * Later, we will add RemoteShellBackend (sends SQL over TCP) without
 * touching the shell loop.
 */
public interface ShellBackend {

    /**
     * Execute a single shell input line and return the result as a string.
     * Never throws; errors are returned as human-readable messages.
     */
    String execute(String input);
}
