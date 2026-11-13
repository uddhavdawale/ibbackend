package org.ib.ibbackend.bootstrap;

import org.ib.ibbackend.entity.RetailerStatus;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.TenantRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.PasswordSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class CloudBootstrapInitializerTest {
    private JdbcTemplate jdbcTemplate;
    private TenantRepository tenantRepository;
    private UserRepository userRepository;
    private PasswordSecurityService passwordSecurityService;
    private CloudBootstrapInitializer initializer;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        tenantRepository = mock(TenantRepository.class);
        userRepository = mock(UserRepository.class);
        passwordSecurityService = mock(PasswordSecurityService.class);
        initializer = new CloudBootstrapInitializer(
                jdbcTemplate,
                tenantRepository,
                userRepository,
                passwordSecurityService,
                "demo@example.com",
                "demo-password-123",
                "admin-one@example.com",
                "admin-one-password-123",
                "admin-two@example.com",
                "admin-two-password-123");
    }

    @Test
    void runCreatesDemoOrgAndThreeActiveAccounts() {
        when(tenantRepository.findByCode("DEMO")).thenReturn(Optional.empty());
        when(tenantRepository.save(any(Tenant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        when(passwordSecurityService.hashNewPassword(anyString())).thenReturn("argon2-hash");
        List<User> savedUsers = new ArrayList<>();
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            savedUsers.add(user);
            return user;
        });

        initializer.run(null);

        assertEquals(3, savedUsers.size());
        assertEquals("Demo Org", savedUsers.get(0).getTenant().getName());
        assertEquals("DEMO", savedUsers.get(0).getTenant().getCode());
        assertEquals(List.of("store_admin", "system_admin", "system_admin"),
                savedUsers.stream().map(User::getRole).toList());
        assertEquals(List.of("demo.admin", "system.admin.one", "system.admin.two"),
                savedUsers.stream().map(User::getUsername).toList());
        for (User user : savedUsers) {
            assertEquals(savedUsers.get(0).getTenant(), user.getTenant());
            assertFalse(user.isDisabled());
            assertEquals(RetailerStatus.APPROVED, user.getApprovalStatus());
            assertTrue(user.isEmailVerified());
            assertEquals("argon2-hash", user.getPasswordHash());
            assertFalse(user.getPasswordHash().contains("demo-password"));
            assertFalse(user.getPasswordHash().contains("admin-one-password"));
            assertFalse(user.getPasswordHash().contains("admin-two-password"));
        }
    }

    @Test
    void runRejectsMissingEmailBeforeWritingBootstrapData() {
        initializer = new CloudBootstrapInitializer(
                jdbcTemplate,
                tenantRepository,
                userRepository,
                passwordSecurityService,
                "",
                "demo-password-123",
                "admin-one@example.com",
                "admin-one-password-123",
                "admin-two@example.com",
                "admin-two-password-123");

        assertThrows(IllegalStateException.class, () -> initializer.run(null));

        verify(tenantRepository, never()).save(any(Tenant.class));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void runLeavesAlreadyProvisionedAccountsUnchanged() {
        Tenant demoOrg = new Tenant();
        demoOrg.setId(7);
        demoOrg.setName("Demo Org");
        demoOrg.setCode("DEMO");
        when(tenantRepository.findByCode("DEMO")).thenReturn(Optional.of(demoOrg));

        String[] usernames = {"demo.admin", "system.admin.one", "system.admin.two"};
        String[] emails = {"demo@example.com", "admin-one@example.com", "admin-two@example.com"};
        String[] roles = {"store_admin", "system_admin", "system_admin"};
        for (int i = 0; i < usernames.length; i++) {
            User existingUser = new User();
            existingUser.setId((long) i + 1);
            existingUser.setUsername(usernames[i]);
            existingUser.setEmail(emails[i]);
            existingUser.setRole(roles[i]);
            existingUser.setTenant(demoOrg);
            existingUser.setDisabled(false);
            existingUser.setApprovalStatus(RetailerStatus.APPROVED);
            existingUser.setEmailVerified(true);
            when(userRepository.findByEmail(emails[i])).thenReturn(Optional.of(existingUser));
            when(userRepository.findByUsername(usernames[i])).thenReturn(Optional.of(existingUser));
        }

        initializer.run(null);

        verify(tenantRepository, never()).save(any(Tenant.class));
        verify(userRepository, never()).save(any(User.class));
    }
}
