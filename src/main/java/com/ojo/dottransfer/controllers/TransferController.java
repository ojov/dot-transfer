package com.ojo.dottransfer.controllers;

import com.ojo.dottransfer.enums.ResponseCode;
import com.ojo.dottransfer.enums.TransactionStatus;
import com.ojo.dottransfer.models.requests.TransferRequest;
import com.ojo.dottransfer.models.responses.DotApiResponse;
import com.ojo.dottransfer.models.responses.TransactionResponse;
import com.ojo.dottransfer.services.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
@Tag(name = "Transfers", description = """
        Moves money between two accounts. The sender bears the transaction fee, so a transfer of
        `amount` debits `amount + fee` from the source and credits `amount` to the destination.""")
public class TransferController {

    private final TransferService transferService;

    @Operation(
            summary = "Process a transfer",
            description = """
                    Always returns 201: a transaction record is created whether or not the money
                    moved. Read `data.status` for the outcome - SUCCESSFUL, INSUFFICIENT_FUND or
                    FAILED - and the envelope's `status` field mirrors it, so a rejected transfer
                    comes back as 201 with `status: false` and its reason in `message`.

                    Send an `Idempotency-Key` header to make retries safe: repeating a request with
                    the same key returns the original transaction instead of transferring again.""")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DotApiResponse<TransactionResponse> transfer(
            @Valid @RequestBody TransferRequest request,

            @Parameter(description = "Client-generated key, at most 64 characters, making this request replay-safe")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        TransactionResponse transaction = transferService.transfer(request, idempotencyKey);

        if (transaction.status() == TransactionStatus.SUCCESSFUL) {
            return DotApiResponse.success(ResponseCode.SUCCESS, transaction.statusMessage(), transaction);
        }
        ResponseCode code = transaction.status() == TransactionStatus.INSUFFICIENT_FUND
                ? ResponseCode.INSUFFICIENT_FUND
                : ResponseCode.TRANSFER_FAILED;
        return DotApiResponse.failure(code, transaction.statusMessage(), transaction);
    }
}
