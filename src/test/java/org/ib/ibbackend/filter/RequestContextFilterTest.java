package org.ib.ibbackend.filter;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RequestContextFilterTest {
    @Test
    void replacesUnsafeRequestIdBeforeEchoingOrLoggingIt() throws Exception {
        RequestContextFilter filter = new RequestContextFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/password/forgot");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("X-Request-Id", "bad\r\nINFO forged");

        filter.doFilter(request, response, (servletRequest, servletResponse) -> { });

        assertThat(UUID.fromString(response.getHeader("X-Request-Id"))).isNotNull();
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void preservesSafeRequestIds() throws Exception {
        RequestContextFilter filter = new RequestContextFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/password/forgot");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("X-Request-Id", "client-request_123");

        filter.doFilter(request, response, (servletRequest, servletResponse) -> { });

        assertThat(response.getHeader("X-Request-Id")).isEqualTo("client-request_123");
    }
}
