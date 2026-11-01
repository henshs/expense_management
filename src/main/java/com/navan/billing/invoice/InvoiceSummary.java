package com.navan.billing.invoice;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Row shape for GET /invoices (list). */
public record InvoiceSummary(String id, String currency, BigDecimal total, int lineCount, OffsetDateTime createdAt) {}
