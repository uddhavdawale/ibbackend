package org.ib.ibbackend.filter;

import jakarta.servlet.FilterChain;
import org.ib.ibbackend.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SimpleRateLimitFilterContactTest {
    @Test
    void limitsContactSubmissionsToFivePerMinutePerClient() throws Exception {
        AuditLogService auditLogService = mock(AuditLogService.class);
        SimpleRateLimitFilter filter = new SimpleRateLimitFilter(auditLogService);
        FilterChain chain = (request, response) -> { };

        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest request = request();
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isNotEqualTo(429);
        }

        MockHttpServletResponse limitedResponse = new MockHttpServletResponse();
        filter.doFilter(request(), limitedResponse, chain);

        assertThat(limitedResponse.getStatus()).isEqualTo(429);
        verify(auditLogService).denied(
                "RATE_LIMIT_TRIGGER", "/api/contact", "Rate limit exceeded for protected endpoint");
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/contact");
        request.setRemoteAddr("192.0.2.10");
        return request;
    }
}
