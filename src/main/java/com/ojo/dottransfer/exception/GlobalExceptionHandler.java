package com.ojo.dottransfer.exception;

import com.ojo.dottransfer.enums.ResponseCode;
import com.ojo.dottransfer.models.responses.DotApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.UUID;

/**
 * Translates exceptions into {@link DotApiResponse} envelopes carrying {@link ErrorDetails}.
 *
 * <p>Every handler logs the throwable itself, so a service can simply {@code throw} and rely on this
 * advice for the diagnostic entry - no need to log before throwing. Two tiers:
 * <ul>
 *   <li><b>Client (4xx)</b>: logged at warn; the thrown message is safe and returned as-is.</li>
 *   <li><b>Server (5xx)</b>: logged at error with a short traceId; the client gets a generic message
 *       plus that id for support correlation. Internal detail is never leaked.</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ---- Client errors (4xx): the message is safe to echo back -------------------------------

    @ExceptionHandler(EntityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public DotApiResponse<ErrorDetails> handleNotFound(EntityNotFoundException ex) {
        return clientError(ResponseCode.RESOURCE_NOT_FOUND, ex.getMessage(), ex);
    }

    @ExceptionHandler(InvalidRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public DotApiResponse<ErrorDetails> handleInvalidTransfer(InvalidRequestException ex) {
        return clientError(ResponseCode.INVALID_REQUEST, ex.getMessage(), ex);
    }

    @ExceptionHandler(AccountNotActiveException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public DotApiResponse<ErrorDetails> handleAccountNotActive(AccountNotActiveException ex) {
        return clientError(ResponseCode.ACCOUNT_NOT_ACTIVE, ex.getMessage(), ex);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public DotApiResponse<ErrorDetails> handleDataIntegrity(DataIntegrityViolationException ex) {
        // Reaching the advice means the service did not recognise the violation as a retryable
        // idempotency race (TransferService handles that one itself) - so it is a genuine conflict.
        return clientError(ResponseCode.DUPLICATE_REQUEST,
                "The request conflicts with an existing record", ex);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public DotApiResponse<ErrorDetails> handleValidation(MethodArgumentNotValidException ex) {
        List<ErrorDetails.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorDetails.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        log.warn("Validation failed: {}", fieldErrors);
        return DotApiResponse.failure(ResponseCode.INVALID_REQUEST, "Validation failed",
                ErrorDetails.ofFieldErrors(fieldErrors));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public DotApiResponse<ErrorDetails> handleMalformed(Exception ex) {
        return clientError(ResponseCode.INVALID_REQUEST,
                "Request body or parameter could not be parsed", ex);
    }

    // ---- Server errors (5xx): generic message + traceId, never the internal detail ------------

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public DotApiResponse<ErrorDetails> handleUnexpected(Exception ex) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        log.error("Unhandled exception [traceId={}]", traceId, ex);
        return DotApiResponse.failure(ResponseCode.INTERNAL_ERROR,
                "Something went wrong. Quote reference " + traceId + " if you contact support.",
                ErrorDetails.ofTrace(traceId));
    }

    private DotApiResponse<ErrorDetails> clientError(ResponseCode code, String message, Exception ex) {
        log.warn("{}: {}", code.getDescription(), message, ex);
        return DotApiResponse.failure(code, message);
    }
}
