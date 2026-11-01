package com.navan.billing.invoice;

import com.navan.billing.common.ApiException;
import com.navan.billing.common.Ids;
import com.navan.billing.common.Page;
import com.navan.billing.common.PageParams;
import com.navan.billing.transaction.Transaction;
import com.navan.billing.transaction.TransactionRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invoice rules. An invoice is a SNAPSHOT: at create/PATCH time we copy the transactions
 * into invoice_lines (+ their tax/fee lines). GET reads only those copies, so later edits to a
 * transaction can never change an existing invoice.
 */
@Service
public class InvoiceService {
    private final InvoiceRepository invoices;
    private final TransactionRepository transactions;

    public InvoiceService(InvoiceRepository invoices, TransactionRepository transactions) {
        this.invoices = invoices;
        this.transactions = transactions;
    }

    @Transactional
    public Invoice create(InvoiceRequest request) {
        List<Transaction> snapshot = loadAndLock(request.resolveIds(), 0L);
        String currency = snapshot.get(0).currency();
        BigDecimal total = InvoiceRollup.grandTotal(snapshot.stream().map(Transaction::total).toList());
        long id = invoices.insertHeader(currency, total, now());
        invoices.insertSnapshot(id, snapshot);
        return read(id);
    }

    @Transactional(readOnly = true)
    public Invoice get(String publicId) {
        long id = parse(publicId);
        invoices.findHeader(id).orElseThrow(() -> notFound(publicId));
        return read(id);
    }

    @Transactional(readOnly = true)
    public Page<InvoiceSummary> list(Integer limit, String cursor) {
        PageParams page = PageParams.of(limit, cursor, Ids.INVOICE_PREFIX);
        List<InvoiceSummary> items = invoices.list(page.afterId(), page.limit());
        String next = items.size() == page.limit() ? items.get(items.size() - 1).id() : null;
        return new Page<>(items, page.limit(), next);
    }

    /**
     * Replaces the set of transactions and rebuilds the snapshot from their CURRENT stored values,
     * in one DB transaction. On any error (404, mixed currency) nothing is written, so the old
     * snapshot stays exactly as it was.
     */
    @Transactional
    public Invoice patch(String publicId, InvoiceRequest request) {
        long id = parse(publicId);
        invoices.lockHeader(id).orElseThrow(() -> notFound(publicId));
        List<Transaction> snapshot = loadAndLock(request.resolveIds(), id);
        String currency = snapshot.get(0).currency();
        BigDecimal total = InvoiceRollup.grandTotal(snapshot.stream().map(Transaction::total).toList());
        invoices.deleteLines(id);
        invoices.insertSnapshot(id, snapshot);
        invoices.updateHeader(id, currency, total, now());
        return read(id);
    }

    /** Deletes the invoice and its snapshot. The underlying transactions are kept (and become editable again). */
    @Transactional
    public void delete(String publicId) {
        long id = parse(publicId);
        invoices.lockHeader(id).orElseThrow(() -> notFound(publicId));
        invoices.delete(id);
    }

    /**
     * Loads the requested transactions with row locks, in request order, and enforces:
     * all exist (404), one currency (409), none already billed on another invoice (409).
     */
    private List<Transaction> loadAndLock(List<String> publicIds, long currentInvoiceId) {
        Set<Long> keys = new TreeSet<>();
        List<String> missing = new ArrayList<>();
        for (String publicId : publicIds) {
            Long key = Ids.parse(publicId, Ids.TRANSACTION_PREFIX);
            if (key == null) {
                missing.add(publicId);
            } else {
                keys.add(key);
            }
        }
        Map<String, Transaction> found = transactions.findByIds(keys, true).stream()
                .collect(Collectors.toMap(Transaction::id, Function.identity()));
        for (String publicId : publicIds) {
            if (!found.containsKey(publicId) && !missing.contains(publicId)) {
                missing.add(publicId);
            }
        }
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "transaction_not_found",
                    "Transactions not found: " + String.join(", ", missing), Map.of("missing_ids", missing));
        }

        List<Transaction> ordered = publicIds.stream().map(found::get).toList();
        Set<String> currencies = ordered.stream().map(Transaction::currency).collect(Collectors.toCollection(TreeSet::new));
        if (currencies.size() > 1) {
            throw new ApiException(HttpStatus.CONFLICT, "mixed_currency",
                    "All transactions on an invoice must share one currency; got " + currencies,
                    Map.of("currencies", List.copyOf(currencies)));
        }

        List<String> billed = invoices.findBilledElsewhere(keys, currentInvoiceId);
        if (!billed.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "transaction_already_invoiced",
                    "Transactions already on another invoice: " + String.join(", ", billed),
                    Map.of("transaction_ids", billed));
        }
        return ordered;
    }

    private Invoice read(long id) {
        InvoiceRepository.Header header = invoices.findHeader(id).orElseThrow();
        List<InvoiceLine> lines = invoices.findLines(id);
        return new Invoice(Ids.invoice(id), header.currency(), header.total(),
                InvoiceRollup.totalsByCategory(lines, header.total()),
                InvoiceRollup.taxRollup(lines), InvoiceRollup.feeRollup(lines), lines,
                header.createdAt(), header.updatedAt());
    }

    private static long parse(String publicId) {
        Long id = Ids.parse(publicId, Ids.INVOICE_PREFIX);
        if (id == null) {
            throw notFound(publicId);
        }
        return id;
    }

    private static ApiException notFound(String publicId) {
        return ApiException.notFound("Invoice " + publicId + " not found");
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }
}
