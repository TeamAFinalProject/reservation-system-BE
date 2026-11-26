package com.asbg.backend.global.presentation;

import java.util.List;

public record FieldErrorResponse(List<FieldError> errors) {
    public FieldErrorResponse {
        errors = List.copyOf(errors);
    }

    public record FieldError(String field, String message) {
    }
}
