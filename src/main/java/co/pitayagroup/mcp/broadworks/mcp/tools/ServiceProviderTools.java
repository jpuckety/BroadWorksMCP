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

    @Tool(name = "broadworks_get_service_provider",
            description = "Get details for a single BroadWorks service provider by id, including its "
                    + "support email, contact (name/number/email), and physical address.")
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
            return toDetail(sp);
        } catch (BroadWorksObjectException ex) {
            log.warn("tool broadworks_get_service_provider failed for serviceProviderId={}: {}",
                    serviceProviderId, ex.getMessage());
            throw new AlpacaException("Service provider not found or not accessible: " + serviceProviderId, ex);
        }
    }

    @Tool(name = "broadworks_modify_service_provider",
            description = "Modify a single BroadWorks service provider. This mutates live BroadWorks data. "
                    + "Only the fields you supply are changed (partial update); omit a field to leave it "
                    + "unchanged. For the clearable fields (supportEmail and each contact/address field) pass "
                    + "an empty string to clear the current value. serviceProviderName and defaultDomain cannot "
                    + "be cleared and are only changed when a non-blank value is supplied. Returns the refreshed "
                    + "service provider detail reflecting the applied state.")
    public ServiceProviderDetail modifyServiceProvider(
            @ToolParam(description = "The id of the service provider to modify") String serviceProviderId,
            @ToolParam(required = false,
                    description = "New display name; omit to leave unchanged (cannot be cleared)")
            String serviceProviderName,
            @ToolParam(required = false,
                    description = "New default domain; omit to leave unchanged (cannot be cleared)")
            String defaultDomain,
            @ToolParam(required = false,
                    description = "New support email; omit to leave unchanged, pass an empty string to clear")
            String supportEmail,
            @ToolParam(required = false,
                    description = "Contact person's name; omit to leave unchanged, pass an empty string to clear")
            String contactName,
            @ToolParam(required = false,
                    description = "Contact phone number; omit to leave unchanged, pass an empty string to clear")
            String contactNumber,
            @ToolParam(required = false,
                    description = "Contact email address; omit to leave unchanged, pass an empty string to clear")
            String contactEmail,
            @ToolParam(required = false,
                    description = "Address line 1; omit to leave unchanged, pass an empty string to clear")
            String addressLine1,
            @ToolParam(required = false,
                    description = "Address line 2; omit to leave unchanged, pass an empty string to clear")
            String addressLine2,
            @ToolParam(required = false,
                    description = "City; omit to leave unchanged, pass an empty string to clear")
            String city,
            @ToolParam(required = false,
                    description = "State or province; omit to leave unchanged, pass an empty string to clear")
            String stateOrProvince,
            @ToolParam(required = false,
                    description = "ZIP or postal code; omit to leave unchanged, pass an empty string to clear")
            String zipOrPostalCode,
            @ToolParam(required = false,
                    description = "Country; omit to leave unchanged, pass an empty string to clear")
            String country,
            @ToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId) {
        log.debug("tool broadworks_modify_service_provider invoked (serviceProviderId={}, resourceId={})",
                serviceProviderId, resourceId);
        final BroadWorksServer server = connect(resourceId);
        try {
            final ServiceProvider sp = ServiceProvider.getPopulatedServiceProvider(server, serviceProviderId);
            final ServiceProvider.ServiceProviderModifyRequest request =
                    new ServiceProvider.ServiceProviderModifyRequest(sp);

            if (isPresent(serviceProviderName)) {
                request.setServiceProviderName(serviceProviderName.trim());
            }
            if (isPresent(defaultDomain)) {
                request.setDefaultDomain(defaultDomain.trim());
            }
            apply(supportEmail, request::setSupportEmail, request::unsetSupportEmail);

            if (contactName != null || contactNumber != null || contactEmail != null) {
                final Contact contact = sp.getContact() != null ? sp.getContact() : new Contact();
                apply(contactName, contact::setContactName, contact::unsetContactName);
                apply(contactNumber, contact::setContactNumber, contact::unsetContactNumber);
                apply(contactEmail, contact::setContactEmail, contact::unsetContactEmail);
                request.setContact(contact);
            }

            if (addressLine1 != null || addressLine2 != null || city != null
                    || stateOrProvince != null || zipOrPostalCode != null || country != null) {
                final StreetAddress address = sp.getAddress() != null ? sp.getAddress() : new StreetAddress();
                apply(addressLine1, address::setAddressLine1, address::unsetAddressLine1);
                apply(addressLine2, address::setAddressLine2, address::unsetAddressLine2);
                apply(city, address::setCity, address::unsetCity);
                apply(stateOrProvince, address::setStateOrProvince, address::unsetStateOrProvince);
                apply(zipOrPostalCode, address::setZipOrPostalCode, address::unsetZipOrPostalCode);
                apply(country, address::setCountry, address::unsetCountry);
                request.setAddress(address);
            }

            final DefaultResponse response = request.fire();
            AlpacaRequests.ensureSuccess(response, "modify service provider " + serviceProviderId);

            final ServiceProvider updated = ServiceProvider.getPopulatedServiceProvider(server, serviceProviderId);
            log.debug("tool broadworks_modify_service_provider succeeded (serviceProviderId={})", serviceProviderId);
            return toDetail(updated);
        } catch (BroadWorksObjectException ex) {
            log.warn("tool broadworks_modify_service_provider failed for serviceProviderId={}: {}",
                    serviceProviderId, ex.getMessage());
            throw new AlpacaException("Service provider not found or not accessible: " + serviceProviderId, ex);
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

    /**
     * Applies a tool-supplied string using set/unset/leave semantics: a {@code null} value leaves the
     * field unchanged, a blank string clears it (via {@code unsetter}), and any other value sets the
     * trimmed string (via {@code setter}).
     */
    private static void apply(String value, Consumer<String> setter, Runnable unsetter) {
        if (value == null) {
            return;
        }
        if (value.isBlank()) {
            unsetter.run();
        } else {
            setter.accept(value.trim());
        }
    }

    private BroadWorksServer connect(String resourceId) {
        final UserInfo user = UserContext.current()
                .orElseThrow(() -> new AlpacaException("No authenticated user in context"));
        return connectionFactory.connect(user.subject(), resourceId);
    }
}
