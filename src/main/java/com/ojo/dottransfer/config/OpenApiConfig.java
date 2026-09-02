package com.ojo.dottransfer.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metadata for the generated OpenAPI document.
 *
 * <p>Without this the docs page is headed "OpenAPI definition" with no description or version -
 * springdoc's placeholder. Since the Scalar UI is how the API is meant to be explored, the landing
 * text is worth writing rather than leaving as a default.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI dotTransferOpenApi() {
        return new OpenAPI().info(new Info()
                .title("dot-transfer API")
                .version("v1")
                .description("""
                        Money transfer between accounts, with a filterable transaction history and \
                        daily summaries.

                        **The money rules.** The transaction fee is 0.5% of the amount, capped at \
                        100, and the *sender* bears it — so a transfer of `amount` debits \
                        `amount + fee` from the source and credits `amount` to the destination. \
                        A nightly job then assigns commission of 20% of the fee to successful \
                        transactions.

                        **Reading a transfer response.** `POST /api/v1/transfers` always returns \
                        201: a transaction record is created whether or not the money moved. The \
                        outcome is in `data.status` — `SUCCESSFUL`, `INSUFFICIENT_FUND` or \
                        `FAILED` — and the envelope's `status` mirrors it. Only genuine request \
                        errors (unknown account, inactive account, a nonsensical request) are 4xx, \
                        and those record nothing.

                        **Retries.** Send an `Idempotency-Key` header to make a request replay-safe; \
                        repeating it with the same key returns the original transaction rather than \
                        transferring again.

                        **No authentication.** Every endpoint here is open, including the `/admin` \
                        job triggers. Spring Security is deliberately not part of this exercise — \
                        see the README for what would change.""")
        );
    }
}
