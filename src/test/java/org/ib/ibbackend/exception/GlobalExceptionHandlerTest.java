package org.ib.ibbackend.exception;

import org.ib.ibbackend.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(mock(AuditLogService.class));

    @Test
    void returnsActionableStatusErrorWithoutStackTrace() {
        String message = "A store admin must initialize cash tracking.";
        var response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.CONFLICT, message));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertFalse((Boolean) response.getBody().get("success"));
        assertEquals(message, response.getBody().get("message"));
        assertFalse(response.getBody().containsKey("trace"));
        assertFalse(response.getBody().containsKey("exception"));
    }

    @Test
    void usesGenericReasonWhenStatusExceptionHasNoMessage() {
        var response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.NOT_FOUND));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("Not Found", response.getBody().get("message"));
    }

    @Test
    void returnsStableSafeCodeForUnconfiguredDocumentStorage() {
        var response = handler.handleDocumentStorageNotConfigured(
                new DocumentStorageNotConfiguredException());

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("DOCUMENT_STORAGE_NOT_CONFIGURED", response.getBody().get("code"));
        assertEquals("Secure document storage is not configured", response.getBody().get("message"));
        assertFalse(response.getBody().containsKey("exception"));
        assertFalse(response.getBody().containsKey("trace"));
    }
}
