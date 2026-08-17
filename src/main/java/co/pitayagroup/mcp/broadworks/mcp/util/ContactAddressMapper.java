package co.pitayagroup.mcp.broadworks.mcp.util;

import java.util.Optional;

import co.pitayagroup.mcp.broadworks.mcp.model.AddressInfo;
import co.pitayagroup.mcp.broadworks.mcp.model.ContactInfo;

import co.ecg.alpaca.toolkit.generated.datatypes.Contact;
import co.ecg.alpaca.toolkit.generated.datatypes.StreetAddress;

/**
 * Maps Alpaca contact/address datatypes to the shared MCP model DTOs.
 *
 * <p>Each mapping is null-safe: a {@code null} source produces a {@code null} DTO, and each
 * {@link java.util.Optional} field is unwrapped to a plain nullable {@link String}.</p>
 *
 * <p>The Alpaca datatype getters return the raw backing field, which is {@code null} (rather than
 * {@link Optional#empty()}) whenever the corresponding element was absent from the OCI response.
 * Unwrapping therefore guards against a {@code null} {@link Optional} to avoid a
 * {@link NullPointerException}.</p>
 */
public final class ContactAddressMapper {

    private ContactAddressMapper() {
    }

    /**
     * Null-safely unwraps an Alpaca datatype {@link Optional} field to a plain nullable value.
     *
     * @param value the (possibly {@code null}) optional returned by an Alpaca getter.
     * @return the contained value, or {@code null} when the optional is {@code null} or empty.
     */
    private static String unwrap(Optional<String> value) {
        return value == null ? null : value.orElse(null);
    }

    /**
     * Maps an Alpaca {@link Contact} to a {@link ContactInfo}.
     *
     * @param contact the source contact, or {@code null}.
     * @return the mapped contact, or {@code null} when the source is {@code null}.
     */
    public static ContactInfo toContact(Contact contact) {
        if (contact == null) {
            return null;
        }
        return new ContactInfo(
                unwrap(contact.getContactName()),
                unwrap(contact.getContactNumber()),
                unwrap(contact.getContactEmail()));
    }

    /**
     * Maps an Alpaca {@link StreetAddress} to an {@link AddressInfo}.
     *
     * @param address the source address, or {@code null}.
     * @return the mapped address, or {@code null} when the source is {@code null}.
     */
    public static AddressInfo toAddress(StreetAddress address) {
        if (address == null) {
            return null;
        }
        return new AddressInfo(
                unwrap(address.getAddressLine1()),
                unwrap(address.getAddressLine2()),
                unwrap(address.getCity()),
                unwrap(address.getStateOrProvince()),
                unwrap(address.getZipOrPostalCode()),
                unwrap(address.getCountry()));
    }
}
