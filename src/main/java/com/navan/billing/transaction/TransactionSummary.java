package com.navan.billing.transaction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Row shape for GET /transactions (list). Lines and metadata are fetched via GET by id. */
public record TransactionSummary(
        String id,
        String externalId,
        String type,
        String currency,
        OffsetDateTime occurredAt,
        BigDecimal total) {}
