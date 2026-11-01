package com.navan.billing.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.navan.billing.common.ApiException;
import com.navan.billing.common.Ids;
import com.navan.billing.common.Money;
import com.navan.billing.common.Page;
import com.navan.billing.common.PageParams;
import com.navan.billing.transaction.metadata.MetadataValidator;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Business rules for transactions: validation, duplicate identity, and the
 * "billed transactions are frozen" rule for PATCH/DELETE.
 */
@Service
public class TransactionService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TransactionService.class);
    private final TransactionRepository repository;
    private final MetadataValidator metadataValidator;
    private final TransactionTemplate transactionTemplate;

    public TransactionService(TransactionRepository repository, MetadataValidator metadataValidator,
                              TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.metadataValidator = metadataValidator;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Creates a transaction. Duplicate identity is decided by external_id:
     * 1. Fast path: if external_id already exists, answer 409 with the existing id (even if the body differs).
     * 2. Race path: two requests can both pass step 1 at the same time. The UNIQUE constraint on
     *    transactions.external_id makes the second INSERT fail; we catch that and answer 409 too.
     * Header + tax lines + fee lines are written in ONE database transaction (all or nothing).
     */
    public Transaction create(CreateTransactionRequest request) {
        TransactionType type = TransactionType.fromWire(request.type())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "unknown_type",
                        "type must be one of: flight, hotel, rail, navan_fee"));

        repository.findIdByExternalId(request.externalId()).ifPresent(existing -> {
            throw duplicate(request.externalId(), existing);
        });

        metadataValidator.validate(type, request.metadata());
        BigDecimal total = Money.amount(request.total(), "total");
        List<TaxLine> taxLines = normaliseTaxLines(request.taxLines());
        List<FeeLine> feeLines = normaliseFeeLines(request.feeLines());
        OffsetDateTime now = now();

        try {
            Long id = transactionTemplate.execute(status -> {
                long newId = repository.insertHeader(request.externalId(), type.wireName(), request.currency(),
                        request.occurredAt(), total, request.metadata(), now);
                repository.replaceTaxLines(newId, taxLines);
                repository.replaceFeeLines(newId, feeLines);
                return newId;
            });
            return get(Ids.transaction(id));
        } catch (DuplicateKeyException e) {
            // Lost the race: another request inserted the same external_id and committed first.
            log.info("Duplicate external_id caught by the UNIQUE constraint: {}", request.externalId());
            String existing = repository.findIdByExternalId(request.externalId())
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "duplicate_external_id",
                            "A transaction with this external_id is being created concurrently; retry"));
            throw duplicate(request.externalId(), existing);
        }
    }

    public Transaction get(String publicId) {
        long id = parse(publicId);
        return repository.findById(id).orElseThrow(() -> notFound(publicId));
    }

    public Page<TransactionSummary> list(Integer limit, String cursor) {
        PageParams page = PageParams.of(limit, cursor, Ids.TRANSACTION_PREFIX);
        List<TransactionSummary> items = repository.list(page.afterId(), page.limit());
        String next = items.size() == page.limit() ? items.get(items.size() - 1).id() : null;
        return new Page<>(items, page.limit(), next);
    }

    /**
     * Updates a transaction. The row is locked first (SELECT ... FOR UPDATE), then we check
     * whether it is on an invoice. Invoice creation takes the same lock, so a PATCH can never
     * slip in between "invoice reads the transaction" and "invoice commits".
     */
    @Transactional
    public Transaction patch(String publicId, PatchTransactionRequest request) {
        if (request.type() != null || request.externalId() != null || request.currency() != null) {
            throw ApiException.badRequest("type, external_id and currency cannot be changed");
        }
        long id = parse(publicId);
        Transaction current = lockOne(id, publicId);
        if (repository.isInvoiced(id)) {
            throw billed(publicId);
        }

        TransactionType type = TransactionType.fromWire(current.type()).orElseThrow();
        JsonNode metadata = request.metadata() != null ? request.metadata() : current.metadata();
        if (request.metadata() != null) {
            metadataValidator.validate(type, metadata);
        }
        OffsetDateTime occurredAt = request.occurredAt() != null ? request.occurredAt() : current.occurredAt();
        BigDecimal total = request.total() != null ? Money.amount(request.total(), "total") : current.total();

        repository.updateHeader(id, occurredAt, total, metadata, now());
        if (request.taxLines() != null) {
            repository.replaceTaxLines(id, normaliseTaxLines(request.taxLines()));
        }
        if (request.feeLines() != null) {
            repository.replaceFeeLines(id, normaliseFeeLines(request.feeLines()));
        }
        return repository.findById(id).orElseThrow();
    }

    /** Deletes a transaction and its lines atomically, unless it is on an invoice (409). */
    @Transactional
    public void delete(String publicId) {
        long id = parse(publicId);
        lockOne(id, publicId);
        if (repository.isInvoiced(id)) {
            throw billed(publicId);
        }
        repository.delete(id);
    }

    private Transaction lockOne(long id, String publicId) {
        return repository.findByIds(List.of(id), true).stream().findFirst()
                .orElseThrow(() -> notFound(publicId));
    }

    private static List<TaxLine> normaliseTaxLines(List<TaxLine> lines) {
        List<TaxLine> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            TaxLine l = lines.get(i);
            out.add(new TaxLine(l.name(), Money.rate(l.rate(), "tax_lines[" + i + "].rate"),
                    Money.amount(l.amount(), "tax_lines[" + i + "].amount")));
        }
        return out;
    }

    private static List<FeeLine> normaliseFeeLines(List<FeeLine> lines) {
        List<FeeLine> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            FeeLine l = lines.get(i);
            out.add(new FeeLine(l.name(), Money.amount(l.amount(), "fee_lines[" + i + "].amount")));
        }
        return out;
    }

    private static long parse(String publicId) {
        Long id = Ids.parse(publicId, Ids.TRANSACTION_PREFIX);
        if (id == null) {
            throw notFound(publicId);
        }
        return id;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }

    private static ApiException notFound(String publicId) {
        return ApiException.notFound("Transaction " + publicId + " not found");
    }

    private static ApiException billed(String publicId) {
        return ApiException.conflict("transaction_invoiced",
                "Transaction " + publicId + " is on an invoice and cannot be changed or deleted");
    }

    private static ApiException duplicate(String externalId, String existingId) {
        return new ApiException(HttpStatus.CONFLICT, "duplicate_external_id",
                "A transaction with external_id '" + externalId + "' already exists",
                Map.of("existing_id", existingId));
    }
}
