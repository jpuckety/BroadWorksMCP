package co.pitayagroup.mcp.broadworks.mcp.tools;

import java.util.Arrays;
import java.util.List;

import co.pitayagroup.mcp.broadworks.auth.session.UserContext;
import co.pitayagroup.mcp.broadworks.auth.session.UserInfo;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaConnectionFactory;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;

import co.ecg.alpaca.toolkit.exception.BroadWorksObjectException;
import co.ecg.alpaca.toolkit.generated.ServiceProvider;
import co.ecg.alpaca.toolkit.generated.datatypes.SearchCriteriaServiceProviderName;
import co.ecg.alpaca.toolkit.generated.enums.SearchMode;
import co.ecg.alpaca.toolkit.generated.tables.ServiceProviderServiceProviderTableRow;
import co.ecg.alpaca.toolkit.messaging.response.Response;
import co.ecg.alpaca.toolkit.model.BroadWorksServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools for BroadWorks service providers, backed by the Alpaca toolkit.
 *
 * <p>Every operation runs against the authenticated user's own BroadWorks connection (resolved by
 * {@code subject} via the {@link AlpacaConnectionFactory}); results are mapped to compact DTOs. No
 * credentials or protocol bodies are logged.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceProviderTools {

    /** Columns emitted for each service-provider row, in positional order. */
    static final List<String> SERVICE_PROVIDER_SCHEMA =
            List.of("serviceProviderId", "serviceProviderName", "enterprise", "resellerId");

    private final AlpacaConnectionFactory connectionFactory;

    @Tool(name = "broadworks_list_service_providers",
            description = "List (or search) the BroadWorks service providers (and enterprises) accessible to "
                    + "the authenticated user. Pass an optional search value to filter by service provider name. "
                    + "Results are paginated and capped server-side (max "
                    + Paging.MAX_PAGE_LIMIT + " per page): pass the returned next_cursor to fetch the next page "
                    + "and inspect has_more/total_matching to know when to stop. Rows are returned in a "
                    + "compact columnar form described by the schema field.")
    public Page listServiceProviders(
            @ToolParam(required = false,
                    description = "Opaque pagination cursor returned as next_cursor by a previous call; "
                            + "omit to start from the first page")
            String cursor,
            @ToolParam(required = false,
                    description = "Maximum rows to return in this page. Clamped to the server ceiling of "
                            + Paging.MAX_PAGE_LIMIT + "; defaults to " + Paging.DEFAULT_PAGE_LIMIT + " when omitted")
            Integer limit,
            @ToolParam(required = false,
                    description = "Optional case-insensitive filter matched against the service provider name; "
                            + "omit to list all")
            String search,
            @ToolParam(required = false,
                    description = "How the search value is matched: STARTSWITH, CONTAINS, or EQUALTO "
                            + "(default CONTAINS). Ignored when search is omitted")
            String searchMode,
            @ToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId) {
        log.debug("tool broadworks_list_service_providers invoked (cursor={}, limit={}, search={}, searchMode={}, "
                        + "resourceId={})", cursor, limit, search, searchMode, resourceId);
        final int offset = Paging.decodeCursor(cursor);
        final int pageLimit = Paging.effectivePageLimit(limit, SERVICE_PROVIDER_SCHEMA.size());
        final BroadWorksServer server = connect(resourceId);
        try {
            final ServiceProvider.ServiceProviderGetListRequest request =
                    new ServiceProvider.ServiceProviderGetListRequest(server);
            if (search != null && !search.isBlank()) {
                request.setSearchCriteriaServiceProviderName(
                        new SearchCriteriaServiceProviderName(searchMode(searchMode), search.trim(), true));
            }
            final ServiceProvider.ServiceProviderGetListResponse response = request.fire();
            ensureSuccess(response, "list service providers");
            final List<ServiceProviderServiceProviderTableRow> serviceProviderTable = response.getServiceProviderTable();
            final List<ServiceProviderSummary> summaries =
                    (serviceProviderTable == null ? List.<ServiceProviderServiceProviderTableRow>of() : serviceProviderTable)
                    .stream()
                    .map(row -> new ServiceProviderSummary(
                            row.getServiceProviderId(),
                            row.getServiceProviderName(),
                            Boolean.parseBoolean(row.getIsEnterprise()),
                            row.getResellerId()))
                    .toList();
            final Page page = toPage(summaries, offset, pageLimit);
            log.debug("tool broadworks_list_service_providers returning {} of {} service provider(s) (hasMore={})",
                    page.returned(), page.totalMatching(), page.hasMore());
            return page;
        } catch (AlpacaException ex) {
            log.warn("tool broadworks_list_service_providers failed: {}", ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("tool broadworks_list_service_providers failed unexpectedly: {}", ex.getMessage());
            throw new AlpacaException("Failed to list service providers", ex);
        }
    }

    /**
     * Maps the service-provider summaries to compact columnar rows and delegates to
     * {@link Paging#toPage} to build a server-capped {@link Page} with pagination metadata.
     */
    static Page toPage(List<ServiceProviderSummary> summaries, int offset, int pageLimit) {
        final List<List<Object>> rows = summaries.stream()
                .map(sp -> Arrays.<Object>asList(
                        sp.serviceProviderId(),
                        sp.serviceProviderName(),
                        sp.enterprise(),
                        sp.resellerId()))
                .toList();
        return Paging.toPage(SERVICE_PROVIDER_SCHEMA, rows, offset, pageLimit,
                "broadworks_list_service_providers", "service providers");
    }

    @Tool(name = "broadworks_get_service_provider",
            description = "Get details for a single BroadWorks service provider by id.")
    public ServiceProviderDetail getServiceProvider(
            @ToolParam(description = "The service provider id") String serviceProviderId,
            @ToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId) {
        log.debug("tool broadworks_get_service_provider invoked (serviceProviderId={}, resourceId={})",
                serviceProviderId, resourceId);
        final BroadWorksServer server = connect(resourceId);
        try {
            final ServiceProvider sp = ServiceProvider.getPopulatedServiceProvider(server, serviceProviderId);
            return new ServiceProviderDetail(
                    sp.getServiceProviderId(),
                    sp.getServiceProviderName(),
                    sp.getDefaultDomain(),
                    Boolean.TRUE.equals(sp.getIsEnterprise()),
                    sp.getResellerId());
        } catch (BroadWorksObjectException ex) {
            log.warn("tool broadworks_get_service_provider failed for serviceProviderId={}: {}",
                    serviceProviderId, ex.getMessage());
            throw new AlpacaException("Service provider not found or not accessible: " + serviceProviderId, ex);
        }
    }

    private BroadWorksServer connect(String resourceId) {
        final UserInfo user = UserContext.current()
                .orElseThrow(() -> new AlpacaException("No authenticated user in context"));
        return connectionFactory.connect(user.subject(), resourceId);
    }

    static void ensureSuccess(Response response, String action) {
        if (response.isErrorResponse()) {
            throw new AlpacaException("BroadWorks failed to " + action
                    + " (error code " + response.getErrorCode() + ")");
        }
    }

    /**
     * Parses a user-supplied search mode into the Alpaca {@link SearchMode} enum, defaulting to
     * {@link SearchMode#CONTAINS} when blank. Matching is case-insensitive.
     *
     * @throws AlpacaException if {@code mode} is not one of STARTSWITH, CONTAINS, or EQUALTO.
     */
    static SearchMode searchMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return SearchMode.CONTAINS;
        }
        try {
            return SearchMode.valueOf(mode.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new AlpacaException("Invalid searchMode '" + mode
                    + "'; expected one of STARTSWITH, CONTAINS, EQUALTO");
        }
    }
}
