package co.pitayagroup.mcp.broadworks.mcp.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import co.pitayagroup.mcp.broadworks.mcp.model.AddressInfo;
import co.pitayagroup.mcp.broadworks.mcp.model.ContactInfo;

import co.ecg.alpaca.toolkit.generated.datatypes.Contact;
import co.ecg.alpaca.toolkit.generated.datatypes.StreetAddress;
import org.junit.jupiter.api.Test;

class ContactAddressMapperTest {

    @Test
    void toContactAndToAddressReturnNullForNullSource() {
        assertThat(ContactAddressMapper.toContact(null)).isNull();
        assertThat(ContactAddressMapper.toAddress(null)).isNull();
    }

    /**
     * Reproduces the reported crash: the Alpaca datatype getters return the raw backing field, which is
     * {@code null} (rather than {@link java.util.Optional#empty()}) whenever the element was absent from the
     * OCI response (or was explicitly unset). The mapper must tolerate that instead of throwing an NPE from
     * {@code getAddressLine1().orElse(...)}.
     */
    @Test
    void toAddressToleratesRawNullOptionalFields() {
        final StreetAddress address = new StreetAddress();
        address.unsetAddressLine1(); // forces the backing Optional field to null
        address.setCountry("USA");

        assertThatCode(() -> ContactAddressMapper.toAddress(address)).doesNotThrowAnyException();

        final AddressInfo info = ContactAddressMapper.toAddress(address);
        assertThat(info.addressLine1()).isNull();
        assertThat(info.country()).isEqualTo("USA");
    }

    @Test
    void toContactToleratesRawNullOptionalFields() {
        final Contact contact = new Contact();
        contact.unsetContactName(); // forces the backing Optional field to null
        contact.setContactEmail("jane@example.com");

        assertThatCode(() -> ContactAddressMapper.toContact(contact)).doesNotThrowAnyException();

        final ContactInfo info = ContactAddressMapper.toContact(contact);
        assertThat(info.contactName()).isNull();
        assertThat(info.contactEmail()).isEqualTo("jane@example.com");
    }

    @Test
    void toAddressUnwrapsPresentValues() {
        final StreetAddress address = new StreetAddress();
        address.setAddressLine1("123 Main St");
        address.setCity("Metropolis");
        address.setCountry("USA");

        final AddressInfo info = ContactAddressMapper.toAddress(address);
        assertThat(info.addressLine1()).isEqualTo("123 Main St");
        assertThat(info.city()).isEqualTo("Metropolis");
        assertThat(info.country()).isEqualTo("USA");
        assertThat(info.stateOrProvince()).isNull();
    }
}
