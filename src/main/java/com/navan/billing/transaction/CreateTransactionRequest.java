package com.navan.billing.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Body of POST /transactions. Type-specific metadata is validated separately by MetadataValidator. */
public record CreateTransactionRequest(
        @NotBlank String type,
        @NotBlank @Size(max = 200) String externalId,
        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO currency code") String currency,
        @NotNull OffsetDateTime occurredAt,
        @NotNull BigDecimal total,
        @NotNull List<@Valid TaxLine> taxLines,
        @NotNull List<@Valid FeeLine> feeLines,
        @NotNull JsonNode metadata) {}
