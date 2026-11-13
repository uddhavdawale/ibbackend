package org.ib.ibbackend.bootstrap;

import org.ib.ibbackend.entity.RetailerStatus;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.TenantRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.PasswordSecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "app.bootstrap.enabled", havingValue = "true")
public class CloudBootstrapInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CloudBootstrapInitializer.class);
    private static final String DEMO_ORG_NAME = "Demo Org";
    private static final String DEMO_ORG_CODE = "DEMO";
    private static final long BOOTSTRAP_LOCK_ID = 2026111301L;

    private final JdbcTemplate jdbcTemplate;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordSecurityService passwordSecurityService;
    private final String demoUserEmail;
    private final String demoUserPassword;
    private final String systemAdminOneEmail;
    private final String systemAdminOnePassword;
    private final String systemAdminTwoEmail;
    private final String systemAdminTwoPassword;

    public CloudBootstrapInitializer(
            JdbcTemplate jdbcTemplate,
            TenantRepository tenantRepository,
            UserRepository userRepository,
            PasswordSecurityService passwordSecurityService,
            @Value("${app.bootstrap.demo.user.email:}") String demoUserEmail,
            @Value("${app.bootstrap.demo.user.password:}") String demoUserPassword,
            @Value("${app.bootstrap.system.admin.one.email:}") String systemAdminOneEmail,
            @Value("${app.bootstrap.system.admin.one.password:}") String systemAdminOnePassword,
            @Value("${app.bootstrap.system.admin.two.email:}") String systemAdminTwoEmail,
            @Value("${app.bootstrap.system.admin.two.password:}") String systemAdminTwoPassword) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordSecurityService = passwordSecurityService;
        this.demoUserEmail = demoUserEmail;
        this.demoUserPassword = demoUserPassword;
        this.systemAdminOneEmail = systemAdminOneEmail;
        this.systemAdminOnePassword = systemAdminOnePassword;
        this.systemAdminTwoEmail = systemAdminTwoEmail;
        this.systemAdminTwoPassword = systemAdminTwoPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<SeedUser> seedUsers = List.of(
                seedUser("demo.admin", demoUserEmail, demoUserPassword, "Demo Administrator", "store_admin"),
                seedUser("system.admin.one", systemAdminOneEmail, systemAdminOnePassword, "System Administrator One", "system_admin"),
                seedUser("system.admin.two", systemAdminTwoEmail, systemAdminTwoPassword, "System Administrator Two", "system_admin")
        );
        validateSeedUsers(seedUsers);

        // Serialize concurrent Cloud Run startup runners while the database is being initialized.
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + BOOTSTRAP_LOCK_ID + ")");

        Tenant demoOrg = tenantRepository.findByCode(DEMO_ORG_CODE)
                .map(this::requireDemoOrg)
                .orElseGet(this::createDemoOrg);

        for (SeedUser seedUser : seedUsers) {
            seedUser(seedUser, demoOrg);
        }

        log.info("Cloud bootstrap data is ready: demo tenant and three bootstrap accounts.");
    }

    private SeedUser seedUser(String username, String email, String password, String ownerName, String role) {
        if (email == null || email.isBlank()) {
            throw new IllegalStateException("Email is required for bootstrap user " + username);
        }
        return new SeedUser(username, email.trim().toLowerCase(Locale.ROOT), password, ownerName, role);
    }

    private void validateSeedUsers(List<SeedUser> seedUsers) {
        for (SeedUser seedUser : seedUsers) {
            passwordSecurityService.validateNewPassword(seedUser.password());
        }
        long distinctEmails = seedUsers.stream().map(SeedUser::email).distinct().count();
        if (distinctEmails != seedUsers.size()) {
            throw new IllegalStateException("Bootstrap users must have distinct email addresses");
        }
    }

    private Tenant requireDemoOrg(Tenant tenant) {
        if (!DEMO_ORG_NAME.equals(tenant.getName())) {
            throw new IllegalStateException("Tenant code DEMO is already assigned to a different organization");
        }
        return tenant;
    }

    private Tenant createDemoOrg() {
        Tenant tenant = new Tenant();
        tenant.setName(DEMO_ORG_NAME);
        tenant.setCode(DEMO_ORG_CODE);
        return tenantRepository.save(tenant);
    }

    private void seedUser(SeedUser seedUser, Tenant demoOrg) {
        Optional<User> byEmail = userRepository.findByEmail(seedUser.email());
        Optional<User> byUsername = userRepository.findByUsername(seedUser.username());
        if (byEmail.isPresent() || byUsername.isPresent()) {
            if (byEmail.isPresent()
                    && byUsername.isPresent()
                    && Objects.equals(byEmail.get().getId(), byUsername.get().getId())
                    && seedUser.role().equalsIgnoreCase(byEmail.get().getRole())
                    && byEmail.get().getTenant() != null
                    && Objects.equals(byEmail.get().getTenant().getId(), demoOrg.getId())
                    && !byEmail.get().isDisabled()
                    && byEmail.get().getApprovalStatus() == RetailerStatus.APPROVED
                    && byEmail.get().isEmailVerified()) {
                return;
            }
            throw new IllegalStateException("Bootstrap identity conflicts with an existing user: " + seedUser.username());
        }

        User user = new User();
        user.setBusinessName(DEMO_ORG_NAME);
        user.setOwnerName(seedUser.ownerName());
        user.setUsername(seedUser.username());
        user.setEmail(seedUser.email());
        user.setPasswordHash(passwordSecurityService.hashNewPassword(seedUser.password()));
        user.setRole(seedUser.role());
        user.setTenant(demoOrg);
        user.setDisabled(false);
        user.setApprovalStatus(RetailerStatus.APPROVED);
        user.setApprovedAt(LocalDateTime.now());
        user.setEmailVerified(true);
        userRepository.save(user);
    }

    private record SeedUser(String username, String email, String password, String ownerName, String role) {
    }
}
