package co.pitayagroup.mcp.broadworks.mcp.util;

import java.util.List;

import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;
import co.pitayagroup.mcp.broadworks.mcp.model.ServiceAuthorization;
import co.pitayagroup.mcp.broadworks.mcp.model.ServiceQuantity;

import co.ecg.alpaca.toolkit.generated.datatypes.GroupServiceAuthorization;
import co.ecg.alpaca.toolkit.generated.datatypes.ServicePackAuthorization;
import co.ecg.alpaca.toolkit.generated.datatypes.UnboundedNonNegativeInt;
import co.ecg.alpaca.toolkit.generated.datatypes.UnboundedPositiveInt;
import co.ecg.alpaca.toolkit.generated.datatypes.UserServiceAuthorization;
import co.ecg.alpaca.toolkit.generated.enums.GroupService;
import co.ecg.alpaca.toolkit.generated.enums.UserService;

/**
 * Shared helpers for translating between admin-facing service data and the Alpaca datatypes used by
 * the service / service-pack tools, decoupled from any single tool bean so every tool set can reuse
 * them.
 *
 * <p>Service names are supplied as their BroadWorks display strings (e.g. {@code Call Waiting}) and
 * validated against the generated {@link UserService} / {@link GroupService} enums via their
 * {@code get(String)} lookup, which matches on the enum's display {@code value()}. An unknown name
 * yields a clear {@link AlpacaException} rather than a silent {@code null}.</p>
 */
public final class ServiceEnums {

    private ServiceEnums() {
    }

    /**
     * Parses a single user service display name (e.g. {@code Call Waiting}) into the {@link UserService}
     * enum.
     *
     * @throws AlpacaException if the name is blank or does not match a known user service.
     */
    public static UserService userService(String name) {
        final String trimmed = requireName(name);
        final UserService service = UserService.get(trimmed);
        if (service == null) {
            throw new AlpacaException("Unknown user service '" + trimmed + "'");
        }
        return service;
    }

    /**
     * Parses a single group service display name (e.g. {@code Auto Attendant}) into the
     * {@link GroupService} enum.
     *
     * @throws AlpacaException if the name is blank or does not match a known group service.
     */
    public static GroupService groupService(String name) {
        final String trimmed = requireName(name);
        final GroupService service = GroupService.get(trimmed);
        if (service == null) {
            throw new AlpacaException("Unknown group service '" + trimmed + "'");
        }
        return service;
    }

    /**
     * Converts a list of user service display names into a {@link UserService} array, validating each
     * name. A {@code null} or empty list yields an empty array.
     */
    public static UserService[] userServices(List<String> names) {
        if (names == null || names.isEmpty()) {
            return new UserService[0];
        }
        return names.stream().map(ServiceEnums::userService).toArray(UserService[]::new);
    }

    /**
     * Converts a list of group service display names into a {@link GroupService} array, validating each
     * name. A {@code null} or empty list yields an empty array.
     */
    public static GroupService[] groupServices(List<String> names) {
        if (names == null || names.isEmpty()) {
            return new GroupService[0];
        }
        return names.stream().map(ServiceEnums::groupService).toArray(GroupService[]::new);
    }

    /** Maps an Alpaca {@link UnboundedPositiveInt} to a {@link ServiceQuantity}, or {@code null}. */
    public static ServiceQuantity toQuantity(UnboundedPositiveInt value) {
        if (value == null) {
            return null;
        }
        return new ServiceQuantity(value.getQuantity(), isUnlimited(value.getUnlimited()));
    }

    /** Maps an Alpaca {@link UnboundedNonNegativeInt} to a {@link ServiceQuantity}, or {@code null}. */
    public static ServiceQuantity toQuantity(UnboundedNonNegativeInt value) {
        if (value == null) {
            return null;
        }
        return new ServiceQuantity(value.getQuantity(), isUnlimited(value.getUnlimited()));
    }

    /**
     * Maps a {@link ServiceQuantity} to an Alpaca {@link UnboundedPositiveInt}, preferring the
     * unlimited flag when set (a single representation); returns {@code null} when the quantity is
     * {@code null} or carries neither an unlimited flag nor a value.
     */
    public static UnboundedPositiveInt toUnboundedPositiveInt(ServiceQuantity quantity) {
        if (quantity == null) {
            return null;
        }
        final UnboundedPositiveInt value = new UnboundedPositiveInt();
        if (quantity.unlimited()) {
            value.setFlagUnlimited();
        } else if (quantity.quantity() != null) {
            value.setQuantity(quantity.quantity());
        } else {
            return null;
        }
        return value;
    }

    /**
     * Maps a {@link ServiceQuantity} to an Alpaca {@link UnboundedNonNegativeInt}, preferring the
     * unlimited flag when set (a single representation); returns {@code null} when the quantity is
     * {@code null} or carries neither an unlimited flag nor a value.
     */
    public static UnboundedNonNegativeInt toUnboundedNonNegativeInt(ServiceQuantity quantity) {
        if (quantity == null) {
            return null;
        }
        final UnboundedNonNegativeInt value = new UnboundedNonNegativeInt();
        if (quantity.unlimited()) {
            value.setFlagUnlimited();
        } else if (quantity.quantity() != null) {
            value.setQuantity(quantity.quantity());
        } else {
            return null;
        }
        return value;
    }

    /**
     * Builds a {@link UserServiceAuthorization} for the named user service. When {@code authorized} is
     * {@code false} the entry is flagged unauthorized (revoking it); otherwise the supplied quantity is
     * applied when present.
     */
    public static UserServiceAuthorization userServiceAuthorization(
            String serviceName, boolean authorized, ServiceQuantity quantity) {
        final UserServiceAuthorization auth = new UserServiceAuthorization(userService(serviceName));
        if (!authorized) {
            auth.setFlagUnauthorized();
        } else {
            final UnboundedPositiveInt value = toUnboundedPositiveInt(quantity);
            if (value != null) {
                auth.setAuthorizedQuantity(value);
            }
        }
        return auth;
    }

    /**
     * Builds a {@link GroupServiceAuthorization} for the named group service. When {@code authorized} is
     * {@code false} the entry is flagged unauthorized (revoking it); otherwise the supplied quantity is
     * applied when present.
     */
    public static GroupServiceAuthorization groupServiceAuthorization(
            String serviceName, boolean authorized, ServiceQuantity quantity) {
        final GroupServiceAuthorization auth = new GroupServiceAuthorization(groupService(serviceName));
        if (!authorized) {
            auth.setFlagUnauthorized();
        } else {
            final UnboundedPositiveInt value = toUnboundedPositiveInt(quantity);
            if (value != null) {
                auth.setAuthorizedQuantity(value);
            }
        }
        return auth;
    }

    /**
     * Builds a {@link ServicePackAuthorization} for the named service pack. When {@code authorized} is
     * {@code false} the entry is flagged unauthorized (revoking it); otherwise the supplied quantity is
     * applied when present.
     */
    public static ServicePackAuthorization servicePackAuthorization(
            String servicePackName, boolean authorized, ServiceQuantity quantity) {
        final ServicePackAuthorization auth = new ServicePackAuthorization(requireName(servicePackName));
        if (!authorized) {
            auth.setFlagUnauthorized();
        } else {
            final UnboundedPositiveInt value = toUnboundedPositiveInt(quantity);
            if (value != null) {
                auth.setAuthorizedQuantity(value);
            }
        }
        return auth;
    }

    /**
     * Converts a list of {@link ServiceAuthorization} entries into a {@link UserServiceAuthorization}
     * array, validating each service name. A {@code null} or empty list yields an empty array so the
     * caller can skip the setter and leave that table untouched (partial-update discipline).
     */
    public static UserServiceAuthorization[] userServiceAuthorizations(List<ServiceAuthorization> entries) {
        if (entries == null || entries.isEmpty()) {
            return new UserServiceAuthorization[0];
        }
        return entries.stream()
                .map(e -> userServiceAuthorization(e.serviceName(), e.authorized(), e.quantity()))
                .toArray(UserServiceAuthorization[]::new);
    }

    /**
     * Converts a list of {@link ServiceAuthorization} entries into a {@link GroupServiceAuthorization}
     * array, validating each service name. A {@code null} or empty list yields an empty array.
     */
    public static GroupServiceAuthorization[] groupServiceAuthorizations(List<ServiceAuthorization> entries) {
        if (entries == null || entries.isEmpty()) {
            return new GroupServiceAuthorization[0];
        }
        return entries.stream()
                .map(e -> groupServiceAuthorization(e.serviceName(), e.authorized(), e.quantity()))
                .toArray(GroupServiceAuthorization[]::new);
    }

    /**
     * Converts a list of {@link ServiceAuthorization} entries into a {@link ServicePackAuthorization}
     * array (the {@code serviceName} field carries the service pack name). A {@code null} or empty list
     * yields an empty array.
     */
    public static ServicePackAuthorization[] servicePackAuthorizations(List<ServiceAuthorization> entries) {
        if (entries == null || entries.isEmpty()) {
            return new ServicePackAuthorization[0];
        }
        return entries.stream()
                .map(e -> servicePackAuthorization(e.serviceName(), e.authorized(), e.quantity()))
                .toArray(ServicePackAuthorization[]::new);
    }

    /** Returns the BroadWorks display name of a {@link UserService}, or {@code null}. */
    public static String displayName(UserService service) {
        return service == null ? null : service.value();
    }

    /** Returns the BroadWorks display name of a {@link GroupService}, or {@code null}. */
    public static String displayName(GroupService service) {
        return service == null ? null : service.value();
    }

    /** Returns the trimmed service name or throws when it is {@code null} or blank. */
    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new AlpacaException("Service name is required");
        }
        return name.trim();
    }

    /** Treats a {@code null} unlimited flag as {@code false}. */
    private static boolean isUnlimited(Boolean unlimited) {
        return Boolean.TRUE.equals(unlimited);
    }
}
