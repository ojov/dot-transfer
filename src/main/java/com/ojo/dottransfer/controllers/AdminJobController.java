package com.ojo.dottransfer.controllers;

import com.ojo.dottransfer.enums.ResponseCode;
import com.ojo.dottransfer.models.responses.CommissionRunResponse;
import com.ojo.dottransfer.models.responses.DailySummaryResponse;
import com.ojo.dottransfer.models.responses.DotApiResponse;
import com.ojo.dottransfer.services.CommissionService;
import com.ojo.dottransfer.services.DailySummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Manual triggers for the two nightly jobs, so their behaviour can be exercised without waiting for
 * midnight or changing a cron.
 *
 * <p>Unsecured, because authentication is outside this exercise's scope. In a real deployment these
 * would sit behind an admin role, and they are deliberately grouped under {@code /admin} so that is
 * a single routing rule rather than a scattered set of them.
 */
@RestController
@RequestMapping("/api/v1/admin/jobs")
@RequiredArgsConstructor
@Tag(name = "Admin - jobs", description = "On-demand triggers for the scheduled operations.")
public class AdminJobController {

    private final CommissionService commissionService;
    private final DailySummaryService dailySummaryService;

    @Operation(
            summary = "Run the commission assessment",
            description = """
                    With no `date`, does exactly what the nightly job does: assesses every closed day
                    that still has unassessed transactions, most recent first. With a `date`, assesses
                    that day alone.

                    Safe to re-run either way - a second run finds nothing outstanding and reports
                    `assessed: 0`.""")
    @PostMapping("/commission")
    public DotApiResponse<CommissionRunResponse> runCommission(
            @Parameter(description = "Business day to assess, yyyy-MM-dd. Omit to clear the whole backlog.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        CommissionRunResponse result = date != null
                ? commissionService.assessDay(date)
                : commissionService.assessBacklog();
        return DotApiResponse.success(ResponseCode.SUCCESS, "Commission assessment complete", result);
    }

    @Operation(
            summary = "Generate a daily summary snapshot",
            description = "Computes and stores the snapshot for the given day, replacing any existing one.")
    @PostMapping("/summary")
    public DotApiResponse<DailySummaryResponse> runSummary(
            @Parameter(description = "Business day to snapshot, yyyy-MM-dd. Defaults to yesterday.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        return DotApiResponse.success(ResponseCode.SUCCESS, "Summary generated",
                dailySummaryService.generateSnapshot(date));
    }
}
