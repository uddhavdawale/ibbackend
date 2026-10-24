package org.ib.ibbackend.exception;

import java.util.ArrayList;
import java.util.List;

public class ValidationException extends RuntimeException {
    private final List<String> errors;

    public ValidationException(String message) {
        super(message);
        this.errors = List.of(message);
    }

    public ValidationException(String message, List<String> errors) {
        super(message);
        this.errors = new ArrayList<>(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
