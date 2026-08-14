package co.pitayagroup.mcp.broadworks.mcp.util;

import co.pitayagroup.mcp.broadworks.mcp.model.AddressInfo;
import co.pitayagroup.mcp.broadworks.mcp.model.ContactInfo;

import co.ecg.alpaca.toolkit.generated.datatypes.Contact;
import co.ecg.alpaca.toolkit.generated.datatypes.StreetAddress;

/**
 * Maps Alpaca contact/address datatypes to the shared MCP model DTOs.
 *
 * <p>Each mapping is null-safe: a {@code null} source produces a {@code null} DTO, and each
 * {@link java.util.Optional} field is unwrapped to a plain nullable {@link String}.</p>
 */
public final class ContactAddressMapper {

    private ContactAddressMapper() {
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
                contact.getContactName().orElse(null),
                contact.getContactNumber().orElse(null),
                contact.getContactEmail().orElse(null));
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
                address.getAddressLine1().orElse(null),
                address.getAddressLine2().orElse(null),
                address.getCity().orElse(null),
                address.getStateOrProvince().orElse(null),
                address.getZipOrPostalCode().orElse(null),
                address.getCountry().orElse(null));
    }
}
