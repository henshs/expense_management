package com.navan.billing.invoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.navan.billing.transaction.FeeLine;
import com.navan.billing.transaction.TaxLine;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** A frozen copy of one transaction, taken when the invoice was created or last PATCHed. */
public record InvoiceLine(
        String transactionId,
        String externalId,
        String type,
        String currency,
        OffsetDateTime occurredAt,
        BigDecimal total,
        List<TaxLine> taxLines,
        List<FeeLine> feeLines,
        JsonNode metadata) {}
