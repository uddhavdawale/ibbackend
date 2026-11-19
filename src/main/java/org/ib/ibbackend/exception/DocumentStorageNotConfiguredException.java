package org.ib.ibbackend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class DocumentStorageNotConfiguredException extends ResponseStatusException {
    public static final String CODE = "DOCUMENT_STORAGE_NOT_CONFIGURED";
    public static final String SAFE_MESSAGE = "Secure document storage is not configured";

    public DocumentStorageNotConfiguredException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, SAFE_MESSAGE);
    }
}
