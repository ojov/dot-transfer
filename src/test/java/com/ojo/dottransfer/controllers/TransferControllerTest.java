package com.ojo.dottransfer.controllers;

import com.ojo.dottransfer.enums.TransactionStatus;
import com.ojo.dottransfer.exception.AccountNotActiveException;
import com.ojo.dottransfer.exception.EntityNotFoundException;
import com.ojo.dottransfer.exception.InvalidRequestException;
import com.ojo.dottransfer.models.responses.TransactionResponse;
import com.ojo.dottransfer.services.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The web edge only: request binding, bean validation, the response envelope and the exception
 * advice's mapping to status codes. The service is mocked, because none of that behaviour depends
 * on money actually moving - and a slice test that booted the database would be testing the wrong
 * layer slowly.
 */
@WebMvcTest(TransferController.class)
class TransferControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransferService transferService;

    private static final String VALID_BODY = """
            {"sourceAccountNumber":"1000000001",
             "destinationAccountNumber":"1000000002",
             "amount":50000.00,
             "description":"Rent"}""";

    private static TransactionResponse response(TransactionStatus status, String message) {
        return new TransactionResponse("DOT-20260902-ABCDEFGH", LocalDate.of(2026, 9, 2),
                "1000000001", "1000000002",
                new BigDecimal("50000.00"), new BigDecimal("100.00"), new BigDecimal("50100.00"),
                "NGN", "Rent", status, message, null, null, null, Instant.parse("2026-09-02T09:00:00Z"));
    }

    @Test
    void successfulTransferIsCreatedAndReportedAsSuccess() throws Exception {
        given(transferService.transfer(any(), any()))
                .willReturn(response(TransactionStatus.SUCCESSFUL, "Transfer completed"));

        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(true))
                .andExpect(jsonPath("$.code").value("00"))
                .andExpect(jsonPath("$.data.status").value("SUCCESSFUL"))
                .andExpect(jsonPath("$.data.billedAmount").value(50100.00))
                .andExpect(jsonPath("$.data.transactionFee").value(100.00));
    }

    @Test
    void rejectedTransferIsStillCreatedButReportedAsFailure() throws Exception {
        given(transferService.transfer(any(), any()))
                .willReturn(response(TransactionStatus.INSUFFICIENT_FUND, "Balance 40000.00 is below the required 50100.00"));

        // 201 because a transaction record was created - the request succeeded even though the
        // transfer did not. The envelope's `status` carries the business outcome.
        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(false))
                .andExpect(jsonPath("$.code").value("03"))
                .andExpect(jsonPath("$.data.status").value("INSUFFICIENT_FUND"))
                .andExpect(jsonPath("$.message").value("Balance 40000.00 is below the required 50100.00"));
    }

    @Test
    void failedTransferReportsTheTransferFailedCode() throws Exception {
        given(transferService.transfer(any(), any()))
                .willReturn(response(TransactionStatus.FAILED, "Transfer could not be completed"));

        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("06"));
    }

    @Test
    void theIdempotencyKeyHeaderIsPassedThrough() throws Exception {
        given(transferService.transfer(any(), any()))
                .willReturn(response(TransactionStatus.SUCCESSFUL, "Transfer completed"));

        mockMvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "order-4471")
                        .content(VALID_BODY))
                .andExpect(status().isCreated());

        verify(transferService).transfer(any(), eq("order-4471"));
    }

    @Test
    void absentIdempotencyKeyIsAllowed() throws Exception {
        given(transferService.transfer(any(), any()))
                .willReturn(response(TransactionStatus.SUCCESSFUL, "Transfer completed"));

        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());

        verify(transferService).transfer(any(), eq(null));
    }

    @Test
    void validationFailuresAreReportedPerField() throws Exception {
        String bad = """
                {"sourceAccountNumber":"","destinationAccountNumber":"1000000002","amount":-5}""";

        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(false))
                .andExpect(jsonPath("$.code").value("01"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                // Per-field detail rather than one opaque message, so a client can highlight
                // the offending inputs.
                .andExpect(jsonPath("$.data.fieldErrors[*].field")
                        .value(containsInAnyOrder("sourceAccountNumber", "amount")));
    }

    @Test
    void amountMustBeAboveZero() throws Exception {
        String zero = """
                {"sourceAccountNumber":"1000000001","destinationAccountNumber":"1000000002","amount":0}""";

        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(zero))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].message").value("must be greater than 0"));
    }

    @Test
    void amountIsLimitedToTwoDecimalPlaces() throws Exception {
        String subKobo = """
                {"sourceAccountNumber":"1000000001","destinationAccountNumber":"1000000002","amount":10.123}""";

        // Rejected rather than silently rounded: the caller should know the amount they sent is
        // not the amount that would move.
        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(subKobo))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].message").value("must have at most 2 decimal places"));
    }

    @Test
    void malformedJsonIsABadRequestNotAServerError() throws Exception {
        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("01"));
    }

    @Test
    void unknownAccountIsNotFound() throws Exception {
        willThrow(new EntityNotFoundException("Account 1000000001 not found"))
                .given(transferService).transfer(any(), any());

        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("02"));
    }

    @Test
    void inactiveAccountIsAConflict() throws Exception {
        willThrow(new AccountNotActiveException("Account 1000000004 is FROZEN and cannot transact"))
                .given(transferService).transfer(any(), any());

        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("04"));
    }

    @Test
    void semanticallyImpossibleRequestIsABadRequest() throws Exception {
        willThrow(new InvalidRequestException("Source and destination accounts must be different"))
                .given(transferService).transfer(any(), any());

        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Source and destination accounts must be different"));
    }

    @Test
    void anUnexpectedFailureIsGenericAndCarriesATraceId() throws Exception {
        willThrow(new IllegalStateException("connection pool exhausted at 10.0.0.4:5432"))
                .given(transferService).transfer(any(), any());

        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("99"))
                .andExpect(jsonPath("$.data.traceId").isNotEmpty())
                // The internal detail must not reach the client - only the correlation id does.
                .andExpect(jsonPath("$.message").value(not(containsString("10.0.0.4"))));
    }
}
