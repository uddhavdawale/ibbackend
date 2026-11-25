package org.ib.ibbackend.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.ib.ibbackend.config.MailMfaProperties;
import org.ib.ibbackend.dto.ContactMessageRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.server.ResponseStatusException;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContactMessageServiceTest {
    @Mock
    private JavaMailSender mailSender;

    @Mock
    private BrevoEmailService brevoEmailService;

    @Test
    void sendsPlainTextContactMessageToSupportWithReplyAddress() throws Exception {
        MailMfaProperties properties = new MailMfaProperties();
        properties.setFrom("noreply@example.com");
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);

        ContactMessageService service = new ContactMessageService(
                mailSender, brevoEmailService, properties,
                "support@example.com", "smtp", "configured");

        service.send(request());

        verify(mailSender).send(message);
        assertThat(message.getAllRecipients()).extracting(Object::toString)
                .containsExactly("support@example.com");
        assertThat(message.getReplyTo()).extracting(Object::toString)
                .containsExactly("customer@example.com");
        assertThat(message.getSubject()).isEqualTo("Contact Us message");
        assertThat(message.getContent().toString())
                .contains("Name: Shop Owner", "Email: customer@example.com", "Phone: Not provided",
                        "I need help with billing.");
    }

    @Test
    void refusesToAcceptMessageWhenNoMailDeliveryIsConfigured() {
        ContactMessageService service = new ContactMessageService(
                mailSender, brevoEmailService, new MailMfaProperties(),
                "support@example.com", "smtp", "");

        assertThatThrownBy(() -> service.send(request()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void usesConfiguredBrevoApiAndSetsReplyToAddress() {
        MailMfaProperties properties = new MailMfaProperties();
        properties.setFrom("sender@example.com");
        when(brevoEmailService.isConfigured()).thenReturn(true);
        ContactMessageService service = new ContactMessageService(
                mailSender, brevoEmailService, properties,
                "support@example.com", "brevo-api", "");

        service.send(request());

        verify(brevoEmailService).send(
                "sender@example.com",
                "support@example.com",
                "Contact Us message",
                "Name: Shop Owner\nEmail: customer@example.com\nPhone: Not provided\n\nMessage:\nI need help with billing.",
                "customer@example.com");
        verify(mailSender, never()).createMimeMessage();
    }

    private ContactMessageRequest request() {
        ContactMessageRequest request = new ContactMessageRequest();
        request.setName("Shop Owner");
        request.setEmail("customer@example.com");
        request.setMessage("I need help with billing.");
        return request;
    }
}
