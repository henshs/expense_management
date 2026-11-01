package com.navan.billing.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** A stored transaction exactly as returned by GET /transactions/{id}. */
public record Transaction(
        String id,
        String externalId,
        String type,
        String currency,
        OffsetDateTime occurredAt,
        BigDecimal total,
        List<TaxLine> taxLines,
        List<FeeLine> feeLines,
        JsonNode metadata,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
