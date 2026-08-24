package co.pitayagroup.mcp.broadworks.mcp.tools;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import co.pitayagroup.mcp.broadworks.auth.session.UserContext;
import co.pitayagroup.mcp.broadworks.auth.session.UserInfo;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaConnectionFactory;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;
import co.pitayagroup.mcp.broadworks.mcp.model.Page;
import co.pitayagroup.mcp.broadworks.mcp.model.ServiceProviderDetail;
import co.pitayagroup.mcp.broadworks.mcp.model.ServiceProviderSummary;
import co.pitayagroup.mcp.broadworks.mcp.util.AlpacaRequests;
import co.pitayagroup.mcp.broadworks.mcp.util.ContactAddressMapper;
import co.pitayagroup.mcp.broadworks.mcp.util.Paging;

import co.ecg.alpaca.toolkit.exception.BroadWorksObjectException;
import co.ecg.alpaca.toolkit.generated.ServiceProvider;
import co.ecg.alpaca.toolkit.generated.datatypes.Contact;
import co.ecg.alpaca.toolkit.generated.datatypes.SearchCriteriaServiceProviderName;
import co.ecg.alpaca.toolkit.generated.datatypes.StreetAddress;
import co.ecg.alpaca.toolkit.generated.tables.ServiceProviderServiceProviderTableRow;
import co.ecg.alpaca.toolkit.messaging.response.DefaultResponse;
import co.ecg.alpaca.toolkit.model.BroadWorksServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

/**
 * MCP tools for BroadWorks service providers, backed by the Alpaca toolkit.
 *
 * <p>Every operation runs against the authenticated user's own BroadWorks connection (resolved by
 * {@code subject} via the {@link AlpacaConnectionFactory}); results are mapped to compact DTOs. No
 * credentials or protocol bodies are logged.</p>
 *
 * <p>When a client supports MCP elicitation, get/modify/create/delete will pause and request any
 * missing required identifiers or create fields rather than failing immediately. Optional filters,
 * pagination, connection {@code resourceId}, contact/address fields, and the delete
 * {@code areYouSure} flag are never elicited.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceProviderTools {

    /** Columns emitted for each service-provider row, in positional order. */
    static final List<String> SERVICE_PROVIDER_SCHEMA =
            List.of("serviceProviderId", "serviceProviderName", "enterprise", "resellerId");

    private final AlpacaConnectionFactory connectionFactory;

    @McpTool(name = "broadworks_list_service_providers",
            description = "List (or search) the BroadWorks service providers (and enterprises) accessible to "
                    + "the authenticated user. Pass an optional search value to filter by service provider name. "
                    + "Results are paginated and capped server-side (max "
                    + Paging.MAX_PAGE_LIMIT + " per page): pass the returned next_cursor to fetch the next page "
                    + "and inspect has_more/total_matching to know when to stop. Rows are returned in a "
                    + "compact columnar form described by the schema field.")
    public Page listServiceProviders(
            @McpToolParam(required = false,
                    description = "Opaque pagination cursor returned as next_cursor by a previous call; "
                            + "omit to start from the first page")
            String cursor,
            @McpToolParam(required = false,
                    description = "Maximum rows to return in this page. Clamped to the server ceiling of "
                            + Paging.MAX_PAGE_LIMIT + "; defaults to " + Paging.DEFAULT_PAGE_LIMIT + " when omitted")
            Integer limit,
            @McpToolParam(required = false,
                    description = "Optional case-insensitive filter matched against the service provider name; "
                            + "omit to list all")
            String search,
            @McpToolParam(required = false,
                    description = "How the search value is matched: STARTSWITH, CONTAINS, or EQUALTO "
                            + "(default CONTAINS). Ignored when search is omitted")
            String searchMode,
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId,
            @McpToolParam(required = false,
                    description = "When true, flush the Alpaca OCI response cache before listing so the "
                            + "result is fetched live from BroadWorks rather than served from cache")
            Boolean refresh) {
        log.debug("tool broadworks_list_service_providers invoked (cursor={}, limit={}, search={}, searchMode={}, "
                        + "resourceId={}, refresh={})", cursor, limit, search, searchMode, resourceId, refresh);
        final int offset = Paging.decodeCursor(cursor);
        final int pageLimit = Paging.effectivePageLimit(limit, SERVICE_PROVIDER_SCHEMA.size());
        final BroadWorksServer server = connect(resourceId);
        AlpacaRequests.refreshIfRequested(server, refresh);
        try {
            final ServiceProvider.ServiceProviderGetListRequest request =
                    new ServiceProvider.ServiceProviderGetListRequest(server);
            if (search != null && !search.isBlank()) {
                request.setSearchCriteriaServiceProviderName(
                        new SearchCriteriaServiceProviderName(AlpacaRequests.searchMode(searchMode), search.trim(), true));
            }
            final ServiceProvider.ServiceProviderGetListResponse response = request.fire();
            AlpacaRequests.ensureSuccess(response, "list service providers");
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

    public Page listServiceProviders(String cursor, Integer limit, String search, String searchMode,
            String resourceId) {
        return listServiceProviders(cursor, limit, search, searchMode, resourceId, null);
    }

    ServiceProviderDetail getServiceProvider(String serviceProviderId, String resourceId) {
        return getServiceProvider(serviceProviderId, resourceId, null, null);
    }

    public ServiceProviderDetail getServiceProvider(String serviceProviderId, String resourceId,
            McpSyncRequestContext requestContext) {
        return getServiceProvider(serviceProviderId, resourceId, null, requestContext);
    }

    @McpTool(name = "broadworks_get_service_provider",
            description = "Get details for a single BroadWorks service provider by id, including its "
                    + "support email, contact (name/number/email), and physical address. "
                    + "If serviceProviderId is omitted and the client supports elicitation, the server will "
                    + "request it. Pass refresh=true to flush the OCI response cache first and fetch live data.")
    public ServiceProviderDetail getServiceProvider(
            @McpToolParam(required = false, description = "The service provider id") String serviceProviderId,
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId,
            @McpToolParam(required = false,
                    description = "When true, flush the Alpaca OCI response cache before reading so the "
                            + "result is fetched live from BroadWorks rather than served from cache")
            Boolean refresh,
            McpSyncRequestContext requestContext) {
        final String spId = require(ToolElicitation.resolveServiceProviderId(serviceProviderId, requestContext),
                "serviceProviderId");
        log.debug("tool broadworks_get_service_provider invoked (serviceProviderId={}, resourceId={}, refresh={})",
                spId, resourceId, refresh);
        final BroadWorksServer server = connect(resourceId);
        AlpacaRequests.refreshIfRequested(server, refresh);
        try {
            final ServiceProvider sp = ServiceProvider.getPopulatedServiceProvider(server, spId);
            return toDetail(sp);
        } catch (BroadWorksObjectException ex) {
            log.warn("tool broadworks_get_service_provider failed for serviceProviderId={}: {}",
                    spId, ex.getMessage());
            throw new AlpacaException("Service provider not found or not accessible: " + spId, ex);
        }
    }

    ServiceProviderDetail modifyServiceProvider(String serviceProviderId, String serviceProviderName,
            String defaultDomain, String supportEmail, String contactName, String contactNumber,
            String contactEmail, String addressLine1, String addressLine2, String city,
            String stateOrProvince, String zipOrPostalCode, String country, String resourceId) {
        return modifyServiceProvider(serviceProviderId, serviceProviderName, defaultDomain, supportEmail,
                contactName, contactNumber, contactEmail, addressLine1, addressLine2, city,
                stateOrProvince, zipOrPostalCode, country, resourceId, null);
    }

    @McpTool(name = "broadworks_modify_service_provider",
            description = "Modify a single BroadWorks service provider. This mutates live BroadWorks data. "
                    + "Only the fields you supply are changed (partial update); omit a field to leave it "
                    + "unchanged. Do NOT send placeholder values such as 'N/A' or '00000' for fields you are "
                    + "not changing — omit them entirely, otherwise BroadWorks may reject the request as "
                    + "invalid. For the clearable fields (supportEmail and each contact/address field) pass "
                    + "an empty string to clear the current value. serviceProviderName and defaultDomain cannot "
                    + "be cleared and are only changed when a non-blank value is supplied. "
                    + "If serviceProviderId is omitted and the client supports elicitation, the server will "
                    + "request it. Returns the refreshed service provider detail reflecting the applied state.",
            annotations = @McpTool.McpAnnotations(destructiveHint = true))
    public ServiceProviderDetail modifyServiceProvider(
            @McpToolParam(required = false, description = "The id of the service provider to modify")
            String serviceProviderId,
            @McpToolParam(required = false,
                    description = "New display name; omit to leave unchanged (cannot be cleared)")
            String serviceProviderName,
            @McpToolParam(required = false,
                    description = "New default domain; omit to leave unchanged (cannot be cleared)")
            String defaultDomain,
            @McpToolParam(required = false,
                    description = "New support email; omit to leave unchanged, pass an empty string to clear")
            String supportEmail,
            @McpToolParam(required = false,
                    description = "Contact person's name; omit to leave unchanged, pass an empty string to clear")
            String contactName,
            @McpToolParam(required = false,
                    description = "Contact phone number; omit to leave unchanged, pass an empty string to clear")
            String contactNumber,
            @McpToolParam(required = false,
                    description = "Contact email address; omit to leave unchanged, pass an empty string to clear")
            String contactEmail,
            @McpToolParam(required = false,
                    description = "Address line 1; omit to leave unchanged, pass an empty string to clear")
            String addressLine1,
            @McpToolParam(required = false,
                    description = "Address line 2; omit to leave unchanged, pass an empty string to clear")
            String addressLine2,
            @McpToolParam(required = false,
                    description = "City; omit to leave unchanged, pass an empty string to clear")
            String city,
            @McpToolParam(required = false,
                    description = "State or province; use the full name (e.g. 'Georgia'), not the two-letter "
                            + "abbreviation (e.g. 'GA'); omit to leave unchanged, pass an empty string to clear")
            String stateOrProvince,
            @McpToolParam(required = false,
                    description = "ZIP or postal code; omit to leave unchanged, pass an empty string to clear")
            String zipOrPostalCode,
            @McpToolParam(required = false,
                    description = "Country; omit to leave unchanged, pass an empty string to clear")
            String country,
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId,
            McpSyncRequestContext requestContext) {
        final String spId = require(ToolElicitation.resolveServiceProviderId(serviceProviderId, requestContext),
                "serviceProviderId");
        log.debug("tool broadworks_modify_service_provider invoked (serviceProviderId={}, resourceId={})",
                spId, resourceId);
        final BroadWorksServer server = connect(resourceId);
        try {
            final ServiceProvider sp = ServiceProvider.getPopulatedServiceProvider(server, spId);
            final ServiceProvider.ServiceProviderModifyRequest request =
                    new ServiceProvider.ServiceProviderModifyRequest(sp);

            if (isPresent(serviceProviderName)) {
                request.setServiceProviderName(serviceProviderName.trim());
            }
            if (isPresent(defaultDomain)) {
                request.setDefaultDomain(defaultDomain.trim());
            }
            apply(supportEmail, request::setSupportEmail);

            // Build fresh Contact/StreetAddress objects and set only the supplied sub-fields. OCI treats an
            // omitted child element as "leave unchanged" and a nil element as "clear", so we must NOT resend the
            // current values of untouched sub-fields: doing so re-validates stale data (e.g. an invalid
            // stateOrProvince), which is what caused BroadWorks error 4015 when only the country was changed.
            if (contactName != null || contactNumber != null || contactEmail != null) {
                final Contact contact = new Contact();
                apply(contactName, contact::setContactName);
                apply(contactNumber, contact::setContactNumber);
                apply(contactEmail, contact::setContactEmail);
                request.setContact(contact);
            }

            if (addressLine1 != null || addressLine2 != null || city != null
                    || stateOrProvince != null || zipOrPostalCode != null || country != null) {
                final StreetAddress address = new StreetAddress();
                apply(addressLine1, address::setAddressLine1);
                apply(addressLine2, address::setAddressLine2);
                apply(city, address::setCity);
                apply(stateOrProvince, address::setStateOrProvince);
                apply(zipOrPostalCode, address::setZipOrPostalCode);
                apply(country, address::setCountry);
                request.setAddress(address);
            }

            final DefaultResponse response = request.fire();
            AlpacaRequests.ensureSuccess(response, "modify service provider " + spId);
            AlpacaRequests.flushResponseCache(server);

            final ServiceProvider updated = ServiceProvider.getPopulatedServiceProvider(server, spId);
            log.debug("tool broadworks_modify_service_provider succeeded (serviceProviderId={})", spId);
            return toDetail(updated);
        } catch (BroadWorksObjectException ex) {
            log.warn("tool broadworks_modify_service_provider failed for serviceProviderId={}: {}",
                    spId, ex.getMessage());
            throw new AlpacaException("Service provider not found or not accessible: " + spId, ex);
        }
    }

    ServiceProviderDetail createServiceProvider(String serviceProviderId, String serviceProviderName,
            String defaultDomain, Boolean enterprise, String supportEmail, String contactName,
            String contactNumber, String contactEmail, String addressLine1, String addressLine2,
            String city, String stateOrProvince, String zipOrPostalCode, String country, String resourceId) {
        return createServiceProvider(serviceProviderId, serviceProviderName, defaultDomain, enterprise,
                supportEmail, contactName, contactNumber, contactEmail, addressLine1, addressLine2,
                city, stateOrProvince, zipOrPostalCode, country, resourceId, null);
    }

    @McpTool(name = "broadworks_create_service_provider",
            description = "Create a new BroadWorks service provider (or enterprise). This mutates live "
                    + "BroadWorks data. serviceProviderId, serviceProviderName and defaultDomain are required; "
                    + "set enterprise=true to provision an enterprise instead of a plain service provider "
                    + "(defaults to false). The optional supportEmail, contact (name/number/email) and address "
                    + "fields are only sent when supplied — omit them entirely rather than sending placeholder "
                    + "values such as 'N/A' or '00000', otherwise BroadWorks may reject the request as invalid. "
                    + "If those required fields are omitted and the client supports elicitation, the server will "
                    + "request them. Fails if a service provider with the same id already exists. Returns the "
                    + "newly created service provider detail.")
    public ServiceProviderDetail createServiceProvider(
            @McpToolParam(required = false,
                    description = "The id for the new service provider (must be unique system-wide)")
            String serviceProviderId,
            @McpToolParam(required = false, description = "The display name for the new service provider")
            String serviceProviderName,
            @McpToolParam(required = false, description = "The default domain for the new service provider")
            String defaultDomain,
            @McpToolParam(required = false,
                    description = "Whether to provision an enterprise rather than a plain service provider; "
                            + "defaults to false when omitted")
            Boolean enterprise,
            @McpToolParam(required = false,
                    description = "Support email; omit to leave unset")
            String supportEmail,
            @McpToolParam(required = false,
                    description = "Contact person's name; omit to leave unset")
            String contactName,
            @McpToolParam(required = false,
                    description = "Contact phone number; omit to leave unset")
            String contactNumber,
            @McpToolParam(required = false,
                    description = "Contact email address; omit to leave unset")
            String contactEmail,
            @McpToolParam(required = false,
                    description = "Address line 1; omit to leave unset")
            String addressLine1,
            @McpToolParam(required = false,
                    description = "Address line 2; omit to leave unset")
            String addressLine2,
            @McpToolParam(required = false,
                    description = "City; omit to leave unset")
            String city,
            @McpToolParam(required = false,
                    description = "State or province; use the full name (e.g. 'Georgia'), not the two-letter "
                            + "abbreviation (e.g. 'GA'); omit to leave unset")
            String stateOrProvince,
            @McpToolParam(required = false,
                    description = "ZIP or postal code; omit to leave unset")
            String zipOrPostalCode,
            @McpToolParam(required = false,
                    description = "Country; omit to leave unset")
            String country,
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId,
            McpSyncRequestContext requestContext) {
        final CreateServiceProviderDetails details = resolveCreateDetails(
                serviceProviderId, serviceProviderName, defaultDomain, requestContext);
        log.debug("tool broadworks_create_service_provider invoked (serviceProviderId={}, enterprise={}, "
                + "resourceId={})", details.serviceProviderId(), enterprise, resourceId);
        final String spId = require(details.serviceProviderId(), "serviceProviderId");
        final String spName = require(details.serviceProviderName(), "serviceProviderName");
        final String domain = require(details.defaultDomain(), "defaultDomain");
        final BroadWorksServer server = connect(resourceId);
        try {
            final ServiceProvider.ServiceProviderConsolidatedAddRequest request =
                    new ServiceProvider.ServiceProviderConsolidatedAddRequest(server, spId);
            request.setServiceProviderName(spName);
            request.setDefaultDomain(domain);
            if (Boolean.TRUE.equals(enterprise)) {
                request.setFlagIsEnterprise();
            }
            apply(supportEmail, request::setSupportEmail);

            if (contactName != null || contactNumber != null || contactEmail != null) {
                final Contact contact = new Contact();
                apply(contactName, contact::setContactName);
                apply(contactNumber, contact::setContactNumber);
                apply(contactEmail, contact::setContactEmail);
                request.setContact(contact);
            }

            if (addressLine1 != null || addressLine2 != null || city != null
                    || stateOrProvince != null || zipOrPostalCode != null || country != null) {
                final StreetAddress address = new StreetAddress();
                apply(addressLine1, address::setAddressLine1);
                apply(addressLine2, address::setAddressLine2);
                apply(city, address::setCity);
                apply(stateOrProvince, address::setStateOrProvince);
                apply(zipOrPostalCode, address::setZipOrPostalCode);
                apply(country, address::setCountry);
                request.setAddress(address);
            }

            final DefaultResponse response = request.fire();
            AlpacaRequests.ensureSuccess(response, "create service provider " + spId);
            AlpacaRequests.flushResponseCache(server);

            final ServiceProvider created = ServiceProvider.getPopulatedServiceProvider(server, spId);
            log.debug("tool broadworks_create_service_provider succeeded (serviceProviderId={})", spId);
            return toDetail(created);
        } catch (BroadWorksObjectException ex) {
            log.warn("tool broadworks_create_service_provider failed for serviceProviderId={}: {}",
                    spId, ex.getMessage());
            throw new AlpacaException("Service provider created but could not be read back: " + spId, ex);
        }
    }

    String deleteServiceProvider(String serviceProviderId, Boolean areYouSure, String resourceId) {
        return deleteServiceProvider(serviceProviderId, areYouSure, resourceId, null);
    }

    @McpTool(name = "broadworks_delete_service_provider",
            description = "Delete a BroadWorks service provider (or enterprise). This mutates live "
                    + "BroadWorks data and is irreversible. BroadWorks may reject the deletion if the "
                    + "service provider still contains groups. This is a two-step operation: first call "
                    + "without areYouSure (or with areYouSure=false) to receive a confirmation prompt; "
                    + "then call again with areYouSure=true to proceed. If the client supports elicitation, "
                    + "confirmation is requested in-band instead of requiring a second call. "
                    + "If serviceProviderId is omitted and the client supports elicitation, the server will "
                    + "request it. Returns a short confirmation message.",
            annotations = @McpTool.McpAnnotations(destructiveHint = true))
    public String deleteServiceProvider(
            @McpToolParam(required = false, description = "The id of the service provider to delete")
            String serviceProviderId,
            @McpToolParam(required = false,
                    description = "Must be true to actually delete. Call first without this (or with false) "
                            + "to get an Are you sure? prompt; then call again with areYouSure=true")
            Boolean areYouSure,
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId,
            McpSyncRequestContext requestContext) {
        final String spId = require(ToolElicitation.resolveServiceProviderId(serviceProviderId, requestContext),
                "serviceProviderId");
        ToolElicitation.requireAreYouSure(areYouSure, "delete service provider '" + spId + "'",
                requestContext);
        log.debug("tool broadworks_delete_service_provider invoked (serviceProviderId={}, resourceId={})",
                spId, resourceId);
        final BroadWorksServer server = connect(resourceId);
        try {
            final ServiceProvider sp = ServiceProvider.getPopulatedServiceProvider(server, spId);
            final ServiceProvider.ServiceProviderDeleteRequest request =
                    new ServiceProvider.ServiceProviderDeleteRequest(sp);
            final DefaultResponse response = request.fire();
            AlpacaRequests.ensureSuccess(response, "delete service provider " + spId);
            AlpacaRequests.flushResponseCache(server);
            log.debug("tool broadworks_delete_service_provider succeeded (serviceProviderId={})", spId);
            return "Deleted service provider '" + spId + "'";
        } catch (AlpacaException ex) {
            log.warn("tool broadworks_delete_service_provider failed: {}", ex.getMessage());
            throw ex;
        } catch (BroadWorksObjectException ex) {
            log.warn("tool broadworks_delete_service_provider failed for serviceProviderId={}: {}",
                    spId, ex.getMessage());
            throw new AlpacaException("Service provider not found or not accessible: " + spId, ex);
        } catch (RuntimeException ex) {
            log.warn("tool broadworks_delete_service_provider failed unexpectedly: {}", ex.getMessage());
            throw new AlpacaException("Failed to delete service provider " + spId, ex);
        }
    }

    /** Maps a populated {@link ServiceProvider} to a compact {@link ServiceProviderDetail} DTO. */
    private static ServiceProviderDetail toDetail(ServiceProvider sp) {
        return new ServiceProviderDetail(
                sp.getServiceProviderId(),
                sp.getServiceProviderName(),
                sp.getDefaultDomain(),
                Boolean.TRUE.equals(sp.getIsEnterprise()),
                sp.getResellerId(),
                sp.getSupportEmail(),
                ContactAddressMapper.toContact(sp.getContact()),
                ContactAddressMapper.toAddress(sp.getAddress()));
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /** Returns the trimmed value or throws when the required field is null or blank. */
    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AlpacaException(field + " is required");
        }
        return value.trim();
    }

    /**
     * Applies a tool-supplied string using set/clear/leave semantics against an Alpaca setter.
     *
     * <p>A {@code null} value leaves the field unchanged (the setter is never called, so the backing
     * optional stays {@code null} and the element is omitted from the request). A blank value clears the
     * field by passing {@code null} to the setter, which the toolkit maps to {@link java.util.Optional#empty()}
     * and serializes as a nil element. Any other value sets the trimmed string.</p>
     *
     * <p>Note: the toolkit's {@code unsetX()} methods set the backing optional to {@code null}, which means
     * "leave unchanged" (omit) rather than "clear" — so clearing must go through the setter with a {@code null}
     * argument, not through {@code unsetX()}.</p>
     */
    private static void apply(String value, Consumer<String> setter) {
        if (value == null) {
            return;
        }
        setter.accept(value.isBlank() ? null : value.trim());
    }

    private BroadWorksServer connect(String resourceId) {
        final UserInfo user = UserContext.current()
                .orElseThrow(() -> new AlpacaException("No authenticated user in context"));
        return connectionFactory.connect(user.subject(), resourceId);
    }

    private static CreateServiceProviderDetails resolveCreateDetails(String serviceProviderId,
            String serviceProviderName, String defaultDomain, McpSyncRequestContext requestContext) {
        if ((!ToolElicitation.isBlank(serviceProviderId) && !ToolElicitation.isBlank(serviceProviderName)
                && !ToolElicitation.isBlank(defaultDomain)) || !ToolElicitation.canElicit(requestContext)) {
            return new CreateServiceProviderDetails(serviceProviderId, serviceProviderName, defaultDomain);
        }
        final CreateServiceProviderDetails elicited = ToolElicitation.elicit(requestContext,
                "Service provider id, display name, and default domain are required.",
                CreateServiceProviderDetails.class,
                "serviceProviderId, serviceProviderName and defaultDomain are required");
        final CreateServiceProviderDetails merged = new CreateServiceProviderDetails(
                ToolElicitation.firstNonBlank(serviceProviderId, elicited.serviceProviderId()),
                ToolElicitation.firstNonBlank(serviceProviderName, elicited.serviceProviderName()),
                ToolElicitation.firstNonBlank(defaultDomain, elicited.defaultDomain()));
        log.info("Elicitation accepted for create service provider (serviceProviderId={})",
                merged.serviceProviderId());
        return merged;
    }

    /**
     * Required create fields the client may supply either as tool arguments or via elicitation.
     */
    record CreateServiceProviderDetails(String serviceProviderId, String serviceProviderName,
            String defaultDomain) {
    }
}
