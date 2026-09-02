package com.ojo.dottransfer.models.requests;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record TransferRequest(

        @NotBlank(message = "sourceAccountNumber is required")
        @Size(max = 20, message = "must be at most 20 characters")
        @Schema(example = "1000000001")
        String sourceAccountNumber,

        @NotBlank(message = "destinationAccountNumber is required")
        @Size(max = 20, message = "must be at most 20 characters")
        @Schema(example = "1000000002")
        String destinationAccountNumber,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "must be greater than 0")
        @Digits(integer = 17, fraction = 2, message = "must have at most 2 decimal places")
        @Schema(example = "50000.00", description = "What the recipient receives. The sender is additionally charged the fee.")
        BigDecimal amount,

        @Size(max = 255, message = "must be at most 255 characters")
        @Schema(example = "Rent for September")
        String description) {}
