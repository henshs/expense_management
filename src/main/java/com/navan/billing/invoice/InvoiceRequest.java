package com.navan.billing.invoice;

import com.navan.billing.common.ApiException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Body of POST /invoices and PATCH /invoices/{id}:
 * either {"transaction_id": "txn_1"} or {"transaction_ids": ["txn_1", "txn_2"]}.
 */
public record InvoiceRequest(String transactionId, List<String> transactionIds) {

    /** The requested ids in request order. The same id twice is a 409 (never double-counted). */
    public List<String> resolveIds() {
        if ((transactionId == null) == (transactionIds == null)) {
            throw ApiException.badRequest("Provide exactly one of transaction_id or transaction_ids");
        }
        List<String> ids = transactionId != null ? List.of(transactionId) : transactionIds;
        if (ids.isEmpty()) {
            throw ApiException.badRequest("transaction_ids must not be empty");
        }
        if (ids.size() > 500) {
            throw ApiException.badRequest("An invoice can hold at most 500 transactions");
        }
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                throw ApiException.badRequest("transaction ids must not be blank");
            }
            if (!seen.add(id)) {
                throw ApiException.conflict("duplicate_transaction_id",
                        "Transaction " + id + " is listed more than once");
            }
        }
        return ids;
    }
}
