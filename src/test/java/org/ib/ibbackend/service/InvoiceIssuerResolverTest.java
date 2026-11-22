package org.ib.ibbackend.service;

import org.ib.ibbackend.entity.Organization;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InvoiceIssuerResolverTest {
    @Test
    void defaultsToStoreForExistingInvoiceRequests() {
        User user = retailerWithTenant("Store One");

        InvoiceIssuerResolver.Snapshot snapshot = InvoiceIssuerResolver.resolve(user, null);

        assertEquals("STORE", snapshot.type());
        assertEquals("Store One", snapshot.name());
    }

    @Test
    void usesAssignedOrganizationNameWhenRequested() {
        User user = retailerWithTenant("Store One");
        Organization organization = new Organization();
        organization.setName("Retail Group");
        user.getTenant().setOrganization(organization);

        InvoiceIssuerResolver.Snapshot snapshot = InvoiceIssuerResolver.resolve(user, "organization");

        assertEquals("ORGANIZATION", snapshot.type());
        assertEquals("Retail Group", snapshot.name());
    }

    @Test
    void snapshotsOrganizationLegalDetailsForGstInvoices() {
        User user = retailerWithTenant("Store One");
        Organization organization = new Organization();
        organization.setName("Retail Group");
        organization.setGstin("27abcde1234f1z5");
        organization.setAddress("Main Road");
        organization.setCity("Pune");
        organization.setState("Maharashtra");
        organization.setPincode("411001");
        organization.setPhone("0201234567");
        organization.setEmail("billing@example.com");
        user.getTenant().setOrganization(organization);
        user.getTenant().setGstin("27ABCDE1234F1Z5");

        InvoiceIssuerResolver.Snapshot snapshot =
                InvoiceIssuerResolver.resolve(user, "ORGANIZATION", true);

        assertEquals("27ABCDE1234F1Z5", snapshot.gstin());
        assertEquals("Main Road, Pune, Maharashtra, 411001", snapshot.address());
        assertEquals("0201234567", snapshot.phone());
        assertEquals("billing@example.com", snapshot.email());
    }

    @Test
    void rejectsOrganizationIssuanceForUnassignedStore() {
        User user = retailerWithTenant("Store One");

        assertThrows(ResponseStatusException.class,
                () -> InvoiceIssuerResolver.resolve(user, "ORGANIZATION"));
    }

    @Test
    void rejectsGstOrganizationInvoiceWithoutRequiredLegalIdentity() {
        User user = retailerWithTenant("Store One");
        Organization organization = new Organization();
        organization.setName("Retail Group");
        organization.setAddress("Main Road");
        user.getTenant().setOrganization(organization);

        assertThrows(ResponseStatusException.class,
                () -> InvoiceIssuerResolver.resolve(user, "ORGANIZATION", true));
    }

    @Test
    void rejectsOrganizationGstInvoiceWhenStoreHasNoGstin() {
        User user = retailerWithTenant("Store One");
        Organization organization = new Organization();
        organization.setName("Retail Group");
        organization.setGstin("27ABCDE1234F1Z5");
        organization.setAddress("Main Road");
        user.getTenant().setOrganization(organization);

        assertThrows(ResponseStatusException.class,
                () -> InvoiceIssuerResolver.resolve(user, "ORGANIZATION", true));
    }

    @Test
    void rejectsUnknownIssuerTypes() {
        User user = retailerWithTenant("Store One");

        assertThrows(ResponseStatusException.class,
                () -> InvoiceIssuerResolver.resolve(user, "OTHER_STORE"));
    }

    @Test
    void storeIssuerUsesTenantLegalDetailsInsteadOfTheCurrentStaffProfile() {
        User user = retailerWithTenant("Store One");
        user.setRole("store_manager");
        user.setPhone("staff-phone");
        user.setEmail("staff@example.com");
        user.setGstin("STAFFGSTIN");
        user.setAddress("Staff address");

        user.getTenant().setGstin("27ABCDE1234F1Z5");
        user.getTenant().setPhone("0201234567");
        user.getTenant().setEmail("store@example.com");
        user.getTenant().setAddress("Store Road");
        user.getTenant().setCity("Pune");
        user.getTenant().setState("Maharashtra");
        user.getTenant().setPincode("411001");

        InvoiceIssuerResolver.Snapshot snapshot = InvoiceIssuerResolver.resolve(user, "STORE", true);

        assertEquals("27ABCDE1234F1Z5", snapshot.gstin());
        assertEquals("Store Road, Pune, Maharashtra, 411001", snapshot.address());
        assertEquals("0201234567", snapshot.phone());
        assertEquals("store@example.com", snapshot.email());
    }

    @Test
    void storeIssuerDoesNotUseStaffPersonalGstinForStoreRegistration() {
        User user = retailerWithTenant("Store One");
        user.setRole("store_manager");
        user.setPhone("staff-phone");
        user.setEmail("staff@example.com");
        user.setGstin("27ABCDE1234F1Z5");
        user.setAddress("Staff address");

        assertThrows(ResponseStatusException.class,
                () -> InvoiceIssuerResolver.resolve(user, "STORE", true));

        InvoiceIssuerResolver.Snapshot snapshot = InvoiceIssuerResolver.resolve(user, "STORE");
        assertNull(snapshot.gstin());
        assertNull(snapshot.address());
        assertNull(snapshot.phone());
        assertNull(snapshot.email());
    }

    private User retailerWithTenant(String storeName) {
        Tenant tenant = new Tenant();
        tenant.setName(storeName);
        User user = new User();
        user.setBusinessName(storeName);
        user.setTenant(tenant);
        return user;
    }
}
