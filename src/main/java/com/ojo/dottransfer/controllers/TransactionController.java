package com.ojo.dottransfer.controllers;

import com.ojo.dottransfer.enums.ResponseCode;
import com.ojo.dottransfer.enums.TransactionStatus;
import com.ojo.dottransfer.models.responses.DailySummaryResponse;
import com.ojo.dottransfer.models.responses.DotApiResponse;
import com.ojo.dottransfer.models.responses.TransactionResponse;
import com.ojo.dottransfer.services.DailySummaryService;
import com.ojo.dottransfer.services.TransactionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Tag(name = "Transactions", description = "Transaction history and daily summaries.")
public class TransactionController {

    private final TransactionQueryService transactionQueryService;
    private final DailySummaryService dailySummaryService;

    @Operation(
            summary = "List transactions",
            description = """
                    Paginated, newest first. Every filter is optional and they combine.
                    `accountNumber` matches transactions on either side of the transfer - money the
                    account sent as well as money it received. `from` and `to` are inclusive
                    business dates (`yyyy-MM-dd`), not instants.""")
    @GetMapping
    public DotApiResponse<Page<TransactionResponse>> list(

            @Parameter(description = "SUCCESSFUL, INSUFFICIENT_FUND or FAILED")
            @RequestParam(required = false) TransactionStatus status,

            @Parameter(description = "Matches the source or the destination account")
            @RequestParam(required = false) String accountNumber,

            @Parameter(description = "Inclusive start date, yyyy-MM-dd")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,

            @Parameter(description = "Inclusive end date, yyyy-MM-dd")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,

            @PageableDefault(size = 20) Pageable pageable) {

        return DotApiResponse.success(ResponseCode.SUCCESS, "Transactions retrieved",
                transactionQueryService.list(status, accountNumber, from, to, pageable));
    }

    @Operation(
            summary = "Summarise a day",
            description = """
                    Totals for one business day, present or past. A closed day is served from the
                    snapshot the nightly job stored (`fromSnapshot: true`); a day with no snapshot -
                    the current one, which is not final, or a past one the job has not covered - is
                    computed live. The current day is flagged `provisional` because it can still
                    change.

                    This is a pure read and never writes a snapshot. To regenerate one, use
                    `POST /api/v1/admin/jobs/summary?date=`.""")
    @GetMapping("/summary")
    public DotApiResponse<DailySummaryResponse> summary(
            @Parameter(description = "Business day to summarise, yyyy-MM-dd. Defaults to today.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        return DotApiResponse.success(ResponseCode.SUCCESS, "Summary retrieved",
                dailySummaryService.getSummary(date));
    }

    @Operation(summary = "Get one transaction by its reference")
    @GetMapping("/{reference}")
    public DotApiResponse<TransactionResponse> get(@PathVariable String reference) {
        return DotApiResponse.success(ResponseCode.SUCCESS, "Transaction retrieved",
                transactionQueryService.getByReference(reference));
    }
}
