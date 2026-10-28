package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.InvoiceRequest;
import org.ib.ibbackend.dto.LoginRequest;
import org.ib.ibbackend.dto.RefreshTokenRequest;
import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.entity.InvoiceItem;
import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.repository.ProductRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.JwtService;
import org.ib.ibbackend.service.PasswordSecurityService;
import org.ib.ibbackend.service.RefreshTokenService;
import org.ib.ibbackend.service.SequenceService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FrontendContractContractTest {

    @Test
    void dashboardMetricsShouldUseFrontendContractKeys() {
        TenantContext.setCurrentTenant(7L);

        DashBoardAPI controller = new DashBoardAPI();
        ReflectionTestUtils.setField(controller, "productRepo", mock(ProductRepository.class));
        ReflectionTestUtils.setField(controller, "invoiceRepo", mock(org.ib.ibbackend.repository.InvoiceRepository.class));

        ResponseEntity<Map<String, Object>> response = controller.getDashboardMetrics();
        Map<String, Object> body = response.getBody();

        assertThat(body).containsKeys("salesToday", "pendingPayments", "lowStock", "ordersToday");
        assertThat(body.get("salesToday")).isInstanceOf(Number.class);
        assertThat(body.get("pendingPayments")).isInstanceOf(Number.class);
        assertThat(body.get("lowStock")).isInstanceOf(Number.class);
        assertThat(body.get("ordersToday")).isInstanceOf(Number.class);

        ResponseEntity<Map<String, Object>> summaryResponse = controller.getDashboardSummary();
        assertThat(summaryResponse.getBody()).containsKeys("salesToday", "pendingPayments", "lowStock", "ordersToday");
    }

    @Test
    void loginPayloadShouldIncludeTenantAndBusinessMetadata() {
        AuthController controller = new AuthController();
        UserRepository userRepo = mock(UserRepository.class);
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
        JwtService jwtService = mock(JwtService.class);
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);

        ReflectionTestUtils.setField(controller, "userRepo", userRepo);
        ReflectionTestUtils.setField(controller, "passwordSecurityService", new PasswordSecurityService(bcrypt));
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
        user.setPasswordHash(bcrypt.encode("secret"));
        user.setRole("STORE_ADMIN");
        user.setTenant(tenant);

        when(userRepo.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(user)).thenReturn("access-token");
        when(jwtService.generateRefreshToken(user)).thenReturn("refresh-token");

        LoginRequest request = new LoginRequest();
        request.setUsername("owner@example.com");
        request.setPassword("secret");

        ResponseEntity<Map<String, Object>> response = controller.login(request);
        Map<String, Object> userPayload = (Map<String, Object>) response.getBody().get("user");

        assertThat(userPayload).containsEntry("tenantId", 7);
        assertThat(userPayload).containsEntry("businessName", "Demo Store");
        assertThat(userPayload).containsEntry("email", "owner@example.com");
    }

    @Test
    void createInvoiceShouldIgnoreClientStatusAndUseServerCalculatedPaymentState() {
        TenantContext.setCurrentTenant(7L);

        InvoiceController controller = new InvoiceController();
        ReflectionTestUtils.setField(controller, "productRepository", mock(ProductRepository.class));
        ReflectionTestUtils.setField(controller, "invoiceRepository", mock(InvoiceRepository.class));
        ReflectionTestUtils.setField(controller, "seqService", mock(SequenceService.class));
        ReflectionTestUtils.setField(controller, "userRepository", mock(UserRepository.class));

        Product product = new Product();
        product.setId(10L);
        product.setName("Notebook");
        product.setSku("NOTEBOOK-007");
        product.setPrice(new BigDecimal("100.00"));
        product.setQuantity(10);

        when(((ProductRepository) ReflectionTestUtils.getField(controller, "productRepository")).findById(10L))
                .thenReturn(Optional.of(product));
        when(((SequenceService) ReflectionTestUtils.getField(controller, "seqService")).nextValue("invoice"))
                .thenReturn(1);
        when(((InvoiceRepository) ReflectionTestUtils.getField(controller, "invoiceRepository")).save(any(Invoice.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        InvoiceRequest request = new InvoiceRequest();
        request.setCustomerName("Walk-in Customer");
        request.setCustomerPhone("0000000000");
        request.setPaymentMethod("UPI");
        request.setStatus("PAID");
        request.setPaidAmount(new BigDecimal("50.00"));

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
        ReflectionTestUtils.setField(controller, "passwordSecurityService", new PasswordSecurityService(new BCryptPasswordEncoder()));
        ReflectionTestUtils.setField(controller, "jwtService", mock(JwtService.class));
        ReflectionTestUtils.setField(controller, "refreshTokenService", mock(RefreshTokenService.class));

        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setUsername("admin");
        request.setRefreshToken("invalid-token");

        when(((UserRepository) ReflectionTestUtils.getField(controller, "userRepo")).findByEmail("admin"))
                .thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.refreshToken(request, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }
}
