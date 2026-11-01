package com.navan.billing.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Body of PATCH /transactions/{id}. Every field is optional; a field that is present replaces
 * the stored value (tax_lines / fee_lines are replaced as whole lists).
 * type, external_id and currency are identity and cannot be changed.
 */
public record PatchTransactionRequest(
        OffsetDateTime occurredAt,
        BigDecimal total,
        List<@Valid TaxLine> taxLines,
        List<@Valid FeeLine> feeLines,
        JsonNode metadata,
        String type,
        String externalId,
        String currency) {}
