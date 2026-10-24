package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.RefreshTokenRequest;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.ProductRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.JwtService;
import org.ib.ibbackend.service.RefreshTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FrontendContractContractTest {

    @Test
    void dashboardMetricsShouldUseFrontendContractKeys() {
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
    void refreshTokenFailureShouldReturnUnauthorized() {
        AuthController controller = new AuthController();
        ReflectionTestUtils.setField(controller, "userRepo", mock(UserRepository.class));
        ReflectionTestUtils.setField(controller, "bcrypt", new BCryptPasswordEncoder());
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
