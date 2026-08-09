package com.broadworks.mcp.mcp;

/**
 * Raised for BroadWorks/Alpaca connection or operation failures surfaced to MCP tool callers.
 *
 * <p>Messages are deliberately safe for a tool response and for logging: they never contain
 * credentials, tokens, or raw protocol bodies.</p>
 */
public class AlpacaException extends RuntimeException {

    public AlpacaException(String message) {
        super(message);
    }

    public AlpacaException(String message, Throwable cause) {
        super(message, cause);
    }
}
