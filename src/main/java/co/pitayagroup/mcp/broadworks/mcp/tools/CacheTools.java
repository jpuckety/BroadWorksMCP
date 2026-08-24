package co.pitayagroup.mcp.broadworks.mcp.tools;

import co.pitayagroup.mcp.broadworks.auth.session.UserContext;
import co.pitayagroup.mcp.broadworks.auth.session.UserInfo;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaConnectionFactory;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;
import co.pitayagroup.mcp.broadworks.mcp.model.CacheFlushResult;
import co.pitayagroup.mcp.broadworks.mcp.util.AlpacaRequests;

import co.ecg.alpaca.toolkit.model.BroadWorksServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools for the Alpaca toolkit's per-connection OCI response cache.
 *
 * <p>Get/list requests are served from that cache (logged as {@code RESPONSE FROM cache}) until they
 * expire. Mutating tools flush automatically after a successful write; this tool is the explicit
 * bypass when data changed out-of-band or a read still looks stale.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheTools {

    static final String FLUSHED_MESSAGE =
            "Alpaca response cache flushed; subsequent gets will fetch live BroadWorks data.";
    static final String NOT_FLUSHED_MESSAGE =
            "Alpaca response cache was empty or could not be cleared.";

    private final AlpacaConnectionFactory connectionFactory;

    @McpTool(name = "broadworks_flush_cache",
            description = "Flush the Alpaca OCI response cache for the current BroadWorks connection so the "
                    + "next get/list calls BroadWorks live instead of returning a cached response. Use this "
                    + "when a get returned stale data after an out-of-band change. Mutating tools already "
                    + "flush automatically after a successful write. Get tools also accept refresh=true to "
                    + "flush immediately before that read.")
    public CacheFlushResult flushCache(
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId) {
        log.debug("tool broadworks_flush_cache invoked (resourceId={})", resourceId);
        final BroadWorksServer server = connect(resourceId);
        final boolean flushed = AlpacaRequests.flushResponseCache(server);
        log.debug("tool broadworks_flush_cache completed (flushed={})", flushed);
        return new CacheFlushResult(flushed, flushed ? FLUSHED_MESSAGE : NOT_FLUSHED_MESSAGE);
    }

    private BroadWorksServer connect(String resourceId) {
        final UserInfo user = UserContext.current()
                .orElseThrow(() -> new AlpacaException("No authenticated user in context"));
        return connectionFactory.connect(user.subject(), resourceId);
    }
}
