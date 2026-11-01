package com.navan.billing.invoice;

import com.navan.billing.transaction.FeeLine;
import com.navan.billing.transaction.TaxLine;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** GET /invoices/{id}: header, totals per category, tax and fee rollups, and the snapshot lines. */
public record Invoice(
        String id,
        String currency,
        BigDecimal total,
        Map<String, BigDecimal> totals,
        List<TaxLine> taxRollup,
        List<FeeLine> feeRollup,
        List<InvoiceLine> lines,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
