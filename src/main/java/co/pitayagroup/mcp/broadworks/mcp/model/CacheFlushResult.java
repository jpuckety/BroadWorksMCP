package co.pitayagroup.mcp.broadworks.mcp.model;

/**
 * Outcome of {@code broadworks_flush_cache}: whether the Alpaca OCI response cache was cleared.
 *
 * @param flushed whether the cache was actually cleared
 * @param message a short, agent-facing summary of the outcome
 */
public record CacheFlushResult(boolean flushed, String message) {
}
