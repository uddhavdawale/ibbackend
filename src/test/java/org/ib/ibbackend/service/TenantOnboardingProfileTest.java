package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.SignupRequest;
import org.ib.ibbackend.repository.TenantRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantOnboardingProfileTest {

    @Test
    void shouldCopyOptionalAddressFieldsToNewTenantDuringSignup() {
        TenantRepository tenantRepository = mock(TenantRepository.class);
        PasswordSecurityService passwordSecurityService = mock(PasswordSecurityService.class);
        when(passwordSecurityService.hashNewPassword("StrongPassword123")).thenReturn("hashed-password");
        when(tenantRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        UserProvisioningService service = new UserProvisioningService(tenantRepository, passwordSecurityService);

        SignupRequest request = new SignupRequest();
        request.setBusinessName("Demo Store");
        request.setOwnerName("Owner");
        request.setUsername("demostore");
        request.setEmail("owner@example.com");
        request.setPasswordHash("StrongPassword123");
        request.setAddress("Main Road");
        request.setCity("Pune");
        request.setPincode("411001");

        var user = service.provisionUser(request, "owner@example.com", "demostore", Optional.empty());

        assertEquals("Main Road", user.getTenant().getAddress());
        assertEquals("Pune", user.getTenant().getCity());
        assertEquals("411001", user.getTenant().getPincode());
        assertEquals("Main Road", user.getAddress());
    }
}
