package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.HsnTaxRateRequest;
import org.ib.ibbackend.entity.HsnTaxRate;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.HsnTaxRateRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.AuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HsnTaxRateAdminControllerTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void systemAdminCanBulkUpsertValidatedRates() {
        HsnTaxRateRepository rateRepository = mock(HsnTaxRateRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        authenticateSystemAdmin(userRepository);
        when(rateRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        HsnTaxRateAdminController controller =
                new HsnTaxRateAdminController(rateRepository, userRepository, auditLogService);
        HsnTaxRateRequest request = new HsnTaxRateRequest();
        request.setHsnCode("1234");
        request.setGstRate(new BigDecimal("18"));

        List<HsnTaxRate> saved = controller.saveRates(List.of(request));

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getHsnCode()).isEqualTo("1234");
        assertThat(saved.get(0).getGstRate()).isEqualByComparingTo("18.00");
        verify(rateRepository).saveAll(anyList());
    }

    @Test
    void rejectsDuplicateCodesInBulkRateUpdates() {
        HsnTaxRateRepository rateRepository = mock(HsnTaxRateRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        authenticateSystemAdmin(userRepository);
        HsnTaxRateAdminController controller =
                new HsnTaxRateAdminController(rateRepository, userRepository, mock(AuditLogService.class));
        HsnTaxRateRequest first = request("1234", "5");
        HsnTaxRateRequest second = request("1234", "18");

        assertThatThrownBy(() -> controller.saveRates(List.of(first, second)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Duplicate HSN/SAC code");
    }

    @Test
    void systemAdminCanUploadCsvRates() {
        HsnTaxRateRepository rateRepository = mock(HsnTaxRateRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        authenticateSystemAdmin(userRepository);
        when(rateRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        HsnTaxRateAdminController controller =
                new HsnTaxRateAdminController(rateRepository, userRepository, mock(AuditLogService.class));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "rates.csv",
                "text/csv",
                "HSN/SAC,GST Rate\n1234,5.00\n998312,18.00\n".getBytes(StandardCharsets.UTF_8));

        Map<String, Object> response = controller.uploadRates(file);

        assertThat(response).containsEntry("success", true).containsEntry("importedCount", 2);
        verify(rateRepository).saveAll(anyList());
    }

    @Test
    void rejectsCsvWithMoreThanOneHsnSacColumn() {
        HsnTaxRateRepository rateRepository = mock(HsnTaxRateRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        authenticateSystemAdmin(userRepository);
        HsnTaxRateAdminController controller =
                new HsnTaxRateAdminController(rateRepository, userRepository, mock(AuditLogService.class));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "rates.csv",
                "text/csv",
                "HSN,SAC,GST Rate\n1234,1234,18\n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> controller.uploadRates(file))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("exactly one HSN/SAC code column");
    }

    private void authenticateSystemAdmin(UserRepository userRepository) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@example.com", null, List.of()));
        User user = new User();
        user.setRole("SYSTEM_ADMIN");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user));
    }

    private HsnTaxRateRequest request(String code, String rate) {
        HsnTaxRateRequest request = new HsnTaxRateRequest();
        request.setHsnCode(code);
        request.setGstRate(new BigDecimal(rate));
        return request;
    }
}
