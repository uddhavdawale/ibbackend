package org.ib.ibbackend.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BrevoEmailServiceTest {
    @Test
    void sendsTextEmailUsingBrevoApiKeyHeader() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BrevoEmailService service = new BrevoEmailService("  test-api-key  ", builder);

        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "test-api-key"))
                .andExpect(content().json("""
                        {
                          "sender": {"email": "sender@example.com"},
                          "to": [{"email": "recipient@example.com"}],
                          "subject": "Verification code",
                          "textContent": "Your code is 123456"
                        }
                        """))
                .andRespond(withSuccess());

        service.send("sender@example.com", "recipient@example.com",
                "Verification code", "Your code is 123456");

        server.verify();
        assertThat(service.isConfigured()).isTrue();
    }

    @Test
    void reportsUnconfiguredApiKey() {
        BrevoEmailService service = new BrevoEmailService("", RestClient.builder());

        assertThat(service.isConfigured()).isFalse();
    }

    @Test
    void includesReplyToWhenSendingContactMessage() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BrevoEmailService service = new BrevoEmailService("test-api-key", builder);

        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "sender": {"email": "sender@example.com"},
                          "to": [{"email": "support@example.com"}],
                          "subject": "Contact Us message",
                          "textContent": "A message",
                          "replyTo": {"email": "customer@example.com"}
                        }
                        """))
                .andRespond(withSuccess());

        service.send("sender@example.com", "support@example.com", "Contact Us message",
                "A message", "customer@example.com");

        server.verify();
    }
}
