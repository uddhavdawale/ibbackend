package org.ib.ibbackend.service;

import org.ib.ibbackend.config.AuthFlowProperties;
import org.ib.ibbackend.config.MailDeliveryConfiguration;
import org.ib.ibbackend.config.MailMfaProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailMfaServiceTest {
    @Test
    void mailDeliveryUsesDisabledByDefaultAndOneWorkerQueue() {
        MailMfaProperties properties = new MailMfaProperties();
        ExecutorService executor = new MailDeliveryConfiguration().mailDeliveryExecutor();
        try {
            MailMfaService service = new MailMfaService(properties, new AuthFlowProperties(), null, executor);
            assertFalse(service.isEnabled());
            assertTrue(executor instanceof java.util.concurrent.ThreadPoolExecutor);
            java.util.concurrent.ThreadPoolExecutor threadPool =
                    (java.util.concurrent.ThreadPoolExecutor) executor;
            assertTrue(threadPool.getMaximumPoolSize() == 1);
            assertTrue(threadPool.getQueue().remainingCapacity() <= 100);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void mailMfaOtpLengthAndExpiryStayWithinSecureLimits() {
        MailMfaProperties properties = new MailMfaProperties();
        properties.setOtpLength(12);
        properties.setExpiryMinutes(30);

        assertTrue(properties.getOtpLength() <= 6);
        assertTrue(properties.getExpiryMinutes() <= 5);
    }

    @Test
    void passwordResetLinkOpensDedicatedRouteAndKeepsTokenInFragment() {
        AuthFlowProperties authFlowProperties = new AuthFlowProperties();
        authFlowProperties.setFrontendBaseUrl("https://frontend.example.com/");
        ExecutorService executor = new MailDeliveryConfiguration().mailDeliveryExecutor();
        try {
            MailMfaService service = new MailMfaService(
                    new MailMfaProperties(), authFlowProperties, null, executor);

            assertThat(service.buildPasswordResetUrl("token+value"))
                    .isEqualTo("https://frontend.example.com/reset-password#resetToken=token%2Bvalue");
        } finally {
            executor.shutdownNow();
        }
    }
}
