package com.maxkuehn.mcstatstsql;

/**
 * Thrown when the configured database cannot be reached or initialized.
 * Fatal at startup so the operator notices immediately.
 */
public class DatabaseException extends RuntimeException {

    public DatabaseException(String message) {
        super(message);
    }

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
