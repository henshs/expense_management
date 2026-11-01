package com.navan.billing.invoice;

import com.navan.billing.common.Money;
import com.navan.billing.transaction.FeeLine;
import com.navan.billing.transaction.TaxLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure arithmetic over snapshot lines (no database, no clock), so it is trivially testable.
 * Rollups are computed from the frozen snapshot, so they are frozen too.
 * - Taxes are grouped by (name, rate): "VAT 7 %" and "VAT 19 %" stay separate lines.
 * - Fees are grouped by name.
 * - Groups appear in the order they are first seen.
 */
public final class InvoiceRollup {
    private InvoiceRollup() {}

    public static BigDecimal grandTotal(List<BigDecimal> totals) {
        return totals.stream().reduce(BigDecimal.ZERO.setScale(Money.SCALE), BigDecimal::add);
    }

    public static List<TaxLine> taxRollup(List<InvoiceLine> lines) {
        Map<List<Object>, TaxLine> groups = new LinkedHashMap<>();
        for (InvoiceLine line : lines) {
            for (TaxLine tax : line.taxLines()) {
                BigDecimal rateKey = tax.rate() == null ? null : tax.rate().stripTrailingZeros();
                List<Object> key = new ArrayList<>();
                key.add(tax.name());
                key.add(rateKey);
                groups.merge(key, tax, (a, b) -> new TaxLine(a.name(), a.rate(), a.amount().add(b.amount())));
            }
        }
        return new ArrayList<>(groups.values());
    }

    public static List<FeeLine> feeRollup(List<InvoiceLine> lines) {
        Map<String, FeeLine> groups = new LinkedHashMap<>();
        for (InvoiceLine line : lines) {
            for (FeeLine fee : line.feeLines()) {
                groups.merge(fee.name(), fee, (a, b) -> new FeeLine(a.name(), a.amount().add(b.amount())));
            }
        }
        return new ArrayList<>(groups.values());
    }

    /**
     * Totals per category: flight / hotel / rail, and Navan fees split by fee_kind
     * (trip_fee, agent_call_fee), plus grand_total.
     */
    public static Map<String, BigDecimal> totalsByCategory(List<InvoiceLine> lines, BigDecimal grandTotal) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (InvoiceLine line : lines) {
            String category = line.type();
            if ("navan_fee".equals(line.type()) && line.metadata().hasNonNull("fee_kind")) {
                category = line.metadata().get("fee_kind").asText();
            }
            totals.merge(Objects.requireNonNull(category), line.total(), BigDecimal::add);
        }
        totals.put("grand_total", grandTotal);
        return totals;
    }
}
