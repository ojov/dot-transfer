package com.ojo.dottransfer.models.responses;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ojo.dottransfer.enums.ResponseCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * The single envelope every endpoint returns, success or failure, so clients parse one shape.
 * Named {@code DotApiResponse} rather than {@code ApiResponse} to avoid colliding with
 * {@code io.swagger.v3.oas.annotations.responses.ApiResponse} in annotated controllers.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DotApiResponse<T>(

        @Schema(example = "00", description = "Machine-readable response code")
        String code,

        @Schema(example = "Success", description = "Short description of the response code")
        String description,

        @Schema(example = "Transfer processed", description = "Human-readable detail")
        String message,

        @Schema(example = "true", description = "Whether the request itself succeeded")
        boolean status,

        T data,

        Instant timestamp) {

    public static <T> DotApiResponse<T> success(ResponseCode responseCode, String message, T data) {
        return new DotApiResponse<>(responseCode.getCode(), responseCode.getDescription(),
                message, true, data, Instant.now());
    }

    public static <T> DotApiResponse<T> failure(ResponseCode responseCode, String message) {
        return failure(responseCode, message, null);
    }

    public static <T> DotApiResponse<T> failure(ResponseCode responseCode, String message, T data) {
        return new DotApiResponse<>(responseCode.getCode(), responseCode.getDescription(),
                message, false, data, Instant.now());
    }
}
