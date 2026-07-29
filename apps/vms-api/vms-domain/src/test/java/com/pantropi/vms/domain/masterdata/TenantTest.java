package com.pantropi.vms.domain.masterdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link Tenant} (US-04.3.1, T-04.3.1.1) — pure, no I/O. */
class TenantTest {

    private static final String EMAIL = "office@acme.example";
    private static final String PHONE = "+880 1711 000000";

    @Test
    @DisplayName("AC-1: a tenant is accepted with a floor and contact details")
    void wellFormedTenant() {
        UUID floor = UUID.randomUUID();
        Tenant tenant = Tenant.draft(" acme ", "  Acme Corporation  ", floor, EMAIL, PHONE);

        assertThat(tenant.code()).isEqualTo("ACME");
        assertThat(tenant.name()).isEqualTo("Acme Corporation");
        assertThat(tenant.floorId()).isEqualTo(floor);
        assertThat(tenant.active()).isTrue();
    }

    @Test
    @DisplayName("a tenant with no floor and no contact details is legitimate")
    void everythingOptionalIsOptional() {
        // The schema makes floor_id, contact_email and contact_phone nullable; an organisation can
        // be registered before its space is assigned.
        Tenant tenant = Tenant.draft("ACME", "Acme", null, null, null);

        assertThat(tenant.floorId()).isNull();
        assertThat(tenant.contactEmail()).isNull();
        assertThat(tenant.contactPhone()).isNull();
    }

    @Test
    @DisplayName("blank contact details become null rather than empty strings")
    void blankContactsBecomeNull() {
        Tenant tenant = Tenant.draft("ACME", "Acme", null, "  ", "  ");

        assertThat(tenant.contactEmail()).isNull();
        assertThat(tenant.contactPhone()).isNull();
    }

    // ---- AC-6: the contact details must not leak ----

    @Test
    @DisplayName("AC-6: toString identifies the tenant without disclosing its contact details")
    void toStringDoesNotLeakContactDetails() {
        // A record's generated toString prints every component. One log.debug("saving " + tenant)
        // anywhere would put an address and a phone number in a log file for its whole retention.
        String rendered = Tenant.draft("ACME", "Acme", null, EMAIL, PHONE).toString();

        assertThat(rendered).doesNotContain(EMAIL).doesNotContain(PHONE);
        assertThat(rendered).doesNotContain("acme.example").doesNotContain("1711");
        assertThat(rendered).contains("ACME");   // still useful for diagnosis
    }

    @Test
    @DisplayName("AC-6: a rejection message names the field but never repeats the value")
    void rejectionDoesNotEchoTheValue() {
        // The response body is one more place the value would then exist.
        assertThatThrownBy(() -> Tenant.draft("ACME", "Acme", null, "not an address", null))
                .isInstanceOf(MasterDataText.InvalidField.class)
                .hasMessageContaining("contactEmail")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("not an address"));
    }

    // ---- AC-5: format validation ----

    @ParameterizedTest
    @ValueSource(strings = {"no-at-sign", "@example.com", "office@", "office@example",
                            "of fice@example.com", "office@@example.com", "office@example."})
    @DisplayName("AC-5: a malformed email is refused naming the field")
    void malformedEmailRefused(String candidate) {
        assertThatThrownBy(() -> Tenant.draft("ACME", "Acme", null, candidate, null))
                .isInstanceOf(MasterDataText.InvalidField.class)
                .satisfies(e -> assertThat(((MasterDataText.InvalidField) e).field)
                        .isEqualTo("contactEmail"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"office@acme.example", "a.b+tag@sub.domain.co.uk", "x@y.zz"})
    @DisplayName("addresses that genuinely work are accepted — the check is deliberately shallow")
    void realAddressesAccepted(String candidate) {
        assertThatCode(() -> Tenant.draft("ACME", "Acme", null, candidate, null))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"+880 1711 000000", "01711-000000", "(02) 5566 7788", "0212345678"})
    @DisplayName("phone numbers keep the shape people actually write them in")
    void realPhoneNumbersAccepted(String candidate) {
        assertThatCode(() -> Tenant.draft("ACME", "Acme", null, null, candidate))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"call me", "12345", "+880 1711 000000 ext. 4", "☎ 12345678"})
    @DisplayName("AC-5: a malformed phone is refused naming the field")
    void malformedPhoneRefused(String candidate) {
        assertThatThrownBy(() -> Tenant.draft("ACME", "Acme", null, null, candidate))
                .isInstanceOf(MasterDataText.InvalidField.class)
                .satisfies(e -> assertThat(((MasterDataText.InvalidField) e).field)
                        .isEqualTo("contactPhone"));
    }

    @Test
    @DisplayName("AC-2: emails differing only in case are both accepted — only code is unique")
    void emailCaseIsNotAnIdentity() {
        // contact_email is citext, but the schema places no unique constraint on it. Two tenants
        // sharing a contact address is a real situation — a managing agent, for instance.
        assertThatCode(() -> {
            Tenant.draft("ONE", "One", null, "Office@Acme.Example", null);
            Tenant.draft("TWO", "Two", null, "office@acme.example", null);
        }).doesNotThrowAnyException();
    }
}
