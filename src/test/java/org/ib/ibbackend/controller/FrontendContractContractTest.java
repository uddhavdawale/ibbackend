package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.InvoiceRequest;
import org.ib.ibbackend.dto.LoginRequest;
import org.ib.ibbackend.dto.LoginResponse;
import org.ib.ibbackend.dto.NonGstInvoiceRequest;
import org.ib.ibbackend.dto.RefreshTokenResponse;
import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.entity.InvoiceItem;
import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.repository.NonGstInvoiceRepository;
import org.ib.ibbackend.repository.ProductRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.JwtService;
import org.ib.ibbackend.service.PaymentCollectionService;
import org.ib.ibbackend.service.PasswordSecurityService;
import org.ib.ibbackend.service.RefreshTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FrontendContractContractTest {

    @AfterEach
    void clearRequestContexts() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void dashboardMetricsShouldUseFrontendContractKeys() {
        TenantContext.setCurrentTenant(7L);

        DashBoardAPI controller = new DashBoardAPI();
        ReflectionTestUtils.setField(controller, "productRepo", mock(ProductRepository.class));
        InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
        NonGstInvoiceRepository nonGstInvoiceRepository = mock(NonGstInvoiceRepository.class);
        ReflectionTestUtils.setField(controller, "invoiceRepo", invoiceRepository);
        ReflectionTestUtils.setField(controller, "nonGstInvoiceRepo", nonGstInvoiceRepository);
        when(invoiceRepository.getTodaySalesByTenantId(7L)).thenReturn(new BigDecimal("100.00"));
        when(nonGstInvoiceRepository.getTodaySalesByTenantId(7L)).thenReturn(new BigDecimal("45.75"));
        when(invoiceRepository.getPendingAmountByTenantId(7L)).thenReturn(new BigDecimal("20.00"));
        when(nonGstInvoiceRepository.getPendingAmountByTenantId(7L)).thenReturn(new BigDecimal("15.25"));
        when(invoiceRepository.countTodayInvoicesByTenantId(7L)).thenReturn(2L);
        when(nonGstInvoiceRepository.countTodayInvoicesByTenantId(7L)).thenReturn(3L);

        ResponseEntity<Map<String, Object>> response = controller.getDashboardMetrics();
        Map<String, Object> body = response.getBody();

        assertThat(body).containsKeys("salesToday", "pendingPayments", "lowStock", "ordersToday");
        assertThat(body.get("salesToday")).isInstanceOf(Number.class);
        assertThat(body.get("pendingPayments")).isInstanceOf(Number.class);
        assertThat(body.get("lowStock")).isInstanceOf(Number.class);
        assertThat(body.get("ordersToday")).isInstanceOf(Number.class);
        assertThat((BigDecimal) body.get("salesToday")).isEqualByComparingTo("145.75");
        assertThat((BigDecimal) body.get("pendingPayments")).isEqualByComparingTo("35.25");
        assertThat(body.get("ordersToday")).isEqualTo(5L);

        ResponseEntity<Map<String, Object>> summaryResponse = controller.getDashboardSummary();
        assertThat(summaryResponse.getBody()).containsKeys("salesToday", "pendingPayments", "lowStock", "ordersToday");
    }

    @Test
    void dashboardStatsShouldIncludeNonGstInvoices() {
        TenantContext.setCurrentTenant(7L);

        DashBoardAPI controller = new DashBoardAPI();
        ProductRepository productRepository = mock(ProductRepository.class);
        InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
        NonGstInvoiceRepository nonGstInvoiceRepository = mock(NonGstInvoiceRepository.class);
        ReflectionTestUtils.setField(controller, "productRepo", productRepository);
        ReflectionTestUtils.setField(controller, "invoiceRepo", invoiceRepository);
        ReflectionTestUtils.setField(controller, "nonGstInvoiceRepo", nonGstInvoiceRepository);

        when(productRepository.findAllWithExpiryByTenantId(7L)).thenReturn(List.of());
        when(invoiceRepository.countByTenantId(7L)).thenReturn(2L);
        when(nonGstInvoiceRepository.countByTenantId(7L)).thenReturn(3L);
        when(invoiceRepository.getTotalRevenueByTenantId(7L)).thenReturn(new BigDecimal("100.00"));
        when(nonGstInvoiceRepository.getTotalRevenueByTenantId(7L)).thenReturn(new BigDecimal("45.00"));
        when(invoiceRepository.getTodaySalesByTenantId(7L)).thenReturn(new BigDecimal("20.00"));
        when(nonGstInvoiceRepository.getTodaySalesByTenantId(7L)).thenReturn(new BigDecimal("10.00"));
        when(invoiceRepository.getPendingAmountByTenantId(7L)).thenReturn(new BigDecimal("5.00"));
        when(nonGstInvoiceRepository.getPendingAmountByTenantId(7L)).thenReturn(new BigDecimal("7.00"));
        when(invoiceRepository.getPendingCountByTenantId(7L)).thenReturn(1L);
        when(nonGstInvoiceRepository.getPendingCountByTenantId(7L)).thenReturn(2L);

        Map<String, Object> stats = controller.getDashboardStats().getBody();

        assertThat(stats).containsEntry("totalOrders", 5L);
        assertThat((BigDecimal) stats.get("totalRevenue")).isEqualByComparingTo("145.00");
        assertThat((BigDecimal) stats.get("salesToday")).isEqualByComparingTo("30.00");
        assertThat((BigDecimal) stats.get("pendingAmount")).isEqualByComparingTo("12.00");
        assertThat(stats).containsEntry("pendingCount", 3L);
    }

    @Test
    void loginPayloadShouldIncludeTenantAndBusinessMetadata() {
        LoginController controller = new LoginController();
        UserRepository userRepo = mock(UserRepository.class);
        Argon2PasswordEncoder argon2 = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        JwtService jwtService = mock(JwtService.class);
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);

        ReflectionTestUtils.setField(controller, "userRepository", userRepo);
        ReflectionTestUtils.setField(controller, "tenantRepository", mock(org.ib.ibbackend.repository.TenantRepository.class));
        ReflectionTestUtils.setField(controller, "passwordSecurityService", new PasswordSecurityService(argon2));
        ReflectionTestUtils.setField(controller, "jwtService", jwtService);
        ReflectionTestUtils.setField(controller, "refreshTokenService", refreshTokenService);

        Tenant tenant = new Tenant();
        tenant.setId(7);
        tenant.setName("Demo Store");
        tenant.setCode("DEMO7");

        User user = new User();
        user.setId(12L);
        user.setOwnerName("Owner");
        user.setEmail("owner@example.com");
        user.setBusinessName("Demo Store");
        user.setPasswordHash(argon2.encode("secret"));
        user.setRole("STORE_ADMIN");
        user.setTenant(tenant);

        when(userRepo.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(user)).thenReturn("access-token");
        when(jwtService.generateRefreshToken(user)).thenReturn("refresh-token");

        LoginRequest request = new LoginRequest();
        request.setUsername("owner@example.com");
        request.setPassword("secret");

        ResponseEntity<LoginResponse> response = controller.login(request);
        Map<String, Object> userPayload = (Map<String, Object>) response.getBody().getUser();

        assertThat(userPayload).containsEntry("tenantId", 7);
        assertThat(userPayload).containsEntry("businessName", "Demo Store");
        assertThat(userPayload).containsEntry("email", "owner@example.com");
    }

    @Test
    void createInvoiceShouldIgnoreClientStatusAndUseServerCalculatedPaymentState() {
        TenantContext.setCurrentTenant(7L);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("owner@example.com", null, List.of()));

        InvoiceController controller = new InvoiceController();
        ReflectionTestUtils.setField(controller, "productRepository", mock(ProductRepository.class));
        ReflectionTestUtils.setField(controller, "invoiceRepository", mock(InvoiceRepository.class));
        UserRepository userRepository = mock(UserRepository.class);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "paymentCollectionService", mock(PaymentCollectionService.class));

        Tenant tenant = new Tenant();
        tenant.setId(7);
        User currentUser = new User();
        currentUser.setId(22L);
        currentUser.setEmail("owner@example.com");
        currentUser.setTenant(tenant);
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(currentUser));

        Product product = new Product();
        product.setId(10L);
        product.setName("Notebook");
        product.setSku("NOTEBOOK-007");
        product.setPrice(new BigDecimal("100.00"));
        product.setQuantity(10);

        when(((ProductRepository) ReflectionTestUtils.getField(controller, "productRepository")).findById(10L))
                .thenReturn(Optional.of(product));
        when(((InvoiceRepository) ReflectionTestUtils.getField(controller, "invoiceRepository")).save(any(Invoice.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceRequest request = new InvoiceRequest();
        request.setCustomerName("Walk-in Customer");
        request.setCustomerPhone("0000000000");
        request.setPaymentMethod("UPI");
        request.setStatus("PAID");
        request.setPaidAmount(new BigDecimal("150.00"));

        InvoiceItem item = new InvoiceItem();
        item.setProductId(10L);
        item.setName("Notebook");
        item.setSku("NOTEBOOK-007");
        item.setHsn("9983");
        item.setGstRate(5);
        item.setPrice(new BigDecimal("100.00"));
        item.setQty(1);
        request.setItems(List.of(item));

        ResponseEntity<?> response = controller.createInvoice(request);
        Invoice saved = (Invoice) response.getBody();

        assertThat(saved).isNotNull();
        assertThat(saved.getPaidAmount()).isEqualByComparingTo("105.00");
        assertThat(saved.getPaymentStatus()).isEqualTo(Invoice.PaymentStatus.PAID);
        assertThat(saved.getStatus()).isEqualTo(Invoice.InvoiceStatus.PAID);
        assertThat(saved.getInvoiceNo()).matches("INV-\\d{12}-\\d{4}");
    }

    @Test
    void invoiceRequestAliasesShouldPreserveReceivedAmounts() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        InvoiceRequest gstRequest = mapper.readValue(
                "{\"receivedAmount\":40.00}", InvoiceRequest.class);
        NonGstInvoiceRequest nonGstRequest = mapper.readValue(
                "{\"amountPaid\":25.50}", NonGstInvoiceRequest.class);

        assertThat(gstRequest.getPaidAmount()).isEqualByComparingTo("40.00");
        assertThat(nonGstRequest.getPaidAmount()).isEqualByComparingTo("25.50");
    }

    @Test
    void partialPaymentStatusShouldTakePrecedenceOverPendingInvoiceStatus() {
        InvoiceController controller = new InvoiceController();

        BigDecimal paidAmount = ReflectionTestUtils.invokeMethod(
                controller, "resolvePaidAmount", new BigDecimal("100.00"),
                new BigDecimal("40.00"), "PENDING", "PARTIAL");

        assertThat(paidAmount).isEqualByComparingTo("40.00");
    }

    @Test
    void shopProfileEndpointShouldReturnDedicatedShopMetadata() {
        UserRepository userRepo = mock(UserRepository.class);
        ShopController controller = new ShopController(userRepo);

        Tenant tenant = new Tenant();
        tenant.setId(7);
        tenant.setName("Demo Store");
        tenant.setCode("DEMO7");

        User user = new User();
        user.setId(12L);
        user.setEmail("owner@example.com");
        user.setOwnerName("Owner");
        user.setBusinessName("Demo Store");
        user.setPhone("9876543210");
        user.setAddress("Main Road");
        user.setCity("Pune");
        user.setPincode("411001");
        user.setTenant(tenant);

        when(userRepo.findByEmail("owner@example.com")).thenReturn(Optional.of(user));

        org.springframework.security.authentication.UsernamePasswordAuthenticationToken auth =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "owner@example.com", null, java.util.Collections.emptyList());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);

        ResponseEntity<Map<String, Object>> response = controller.getCurrentShop();
        Map<String, Object> shop = (Map<String, Object>) response.getBody().get("shop");

        assertThat(shop).containsEntry("tenantId", 7);
        assertThat(shop).containsEntry("businessName", "Demo Store");
        assertThat(shop).containsEntry("shopName", "Demo Store");
        assertThat(shop).containsEntry("tenantCode", "DEMO7");
        assertThat(shop).containsEntry("email", "owner@example.com");

        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void refreshTokenFailureShouldReturnUnauthorized() {
        AuthController controller = new AuthController();
        ReflectionTestUtils.setField(controller, "userRepo", mock(UserRepository.class));
        ReflectionTestUtils.setField(controller, "jwtService", mock(JwtService.class));
        ReflectionTestUtils.setField(controller, "refreshTokenService", mock(RefreshTokenService.class));

        ResponseEntity<?> response = controller.refreshToken(null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getHeaders().getFirst("Cache-Control")).contains("no-store");
        assertThat(response.getHeaders().getFirst("Set-Cookie"))
                .contains("refreshToken=").contains("Path=/api/auth").contains("Max-Age=0");
    }

    @Test
    void refreshTokenShouldRotateCookieAndReturnOnlyAccessToken() throws Exception {
        AuthController controller = new AuthController();
        UserRepository userRepo = mock(UserRepository.class);
        JwtService jwtService = mock(JwtService.class);
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        ReflectionTestUtils.setField(controller, "userRepo", userRepo);
        ReflectionTestUtils.setField(controller, "jwtService", jwtService);
        ReflectionTestUtils.setField(controller, "refreshTokenService", refreshTokenService);

        User user = new User();
        user.setId(19L);
        user.setEmail("owner@example.com");
        user.setRole("STORE_ADMIN");
        Tenant tenant = new Tenant();
        tenant.setId(19);
        user.setTenant(tenant);
        Claims claims = Jwts.claims();
        claims.setSubject(user.getEmail());
        when(jwtService.extractRefreshClaims("old-refresh-token")).thenReturn(claims);
        when(userRepo.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(user)).thenReturn("new-access-token");
        when(jwtService.generateRefreshToken(user)).thenReturn("candidate-refresh-token");
        when(jwtService.getRefreshTokenExpiryMs()).thenReturn(604800000L);
        when(refreshTokenService.rotateToken(eq(user), eq("old-refresh-token"), anyString()))
                .thenReturn(Optional.of("rotated-refresh-token"));

        ResponseEntity<?> response = controller.refreshToken("old-refresh-token", new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((RefreshTokenResponse) response.getBody()).getAccessToken()).isEqualTo("new-access-token");
        assertThat(new ObjectMapper().writeValueAsString(response.getBody()))
                .doesNotContain("refreshToken", "rotated-refresh-token");
        assertThat(response.getHeaders().getFirst("Cache-Control")).contains("no-store");
        assertThat(response.getHeaders().get("Set-Cookie"))
                .anySatisfy(cookie -> assertThat(cookie)
                        .contains("rotated-refresh-token").contains("Path=/api/auth").contains("HttpOnly"));
    }

    @Test
    void authLoginRouteUsesMfaAwareLoginController() throws NoSuchMethodException {
        PostMapping mapping = LoginController.class
                .getMethod("login", LoginRequest.class)
                .getAnnotation(PostMapping.class);

        assertThat(mapping).isNotNull();
        assertThat(mapping.path()).contains("/api/auth/login");
    }
}
