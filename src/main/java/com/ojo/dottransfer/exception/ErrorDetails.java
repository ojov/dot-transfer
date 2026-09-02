package com.ojo.dottransfer.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Client-safe supplementary detail attached to a failed response. Present only when it adds
 * something beyond the envelope: per-field validation errors, or a correlation id for a server-side
 * failure. Never carries stack traces or internal messages.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorDetails(

        @Schema(description = "Per-field validation errors, when applicable")
        List<FieldError> fieldErrors,

        @Schema(example = "3f9a1c7e", description = "Correlation id - quote this to support; it matches the server log entry")
        String traceId) {

    public record FieldError(
            @Schema(example = "amount") String field,
            @Schema(example = "must be greater than 0") String message) {}

    public static ErrorDetails ofFieldErrors(List<FieldError> fieldErrors) {
        return new ErrorDetails(fieldErrors, null);
    }

    public static ErrorDetails ofTrace(String traceId) {
        return new ErrorDetails(null, traceId);
    }
}
