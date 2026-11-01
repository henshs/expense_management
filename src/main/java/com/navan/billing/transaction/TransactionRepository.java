package com.navan.billing.transaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navan.billing.common.Ids;
import com.navan.billing.common.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * All SQL for transactions and their tax/fee lines. No business rules live here;
 * callers (TransactionService, InvoiceService) decide what is allowed and own the DB transaction.
 */
@Repository
public class TransactionRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TransactionRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Inserts the header row. Throws DuplicateKeyException if external_id already exists. */
    public long insertHeader(String externalId, String type, String currency, OffsetDateTime occurredAt,
                             java.math.BigDecimal total, JsonNode metadata, OffsetDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO transactions (external_id, type, currency, occurred_at, total, metadata, created_at, updated_at)
                VALUES (:externalId, :type, :currency, :occurredAt, :total, :metadata, :now, :now)
                """,
                new MapSqlParameterSource()
                        .addValue("externalId", externalId)
                        .addValue("type", type)
                        .addValue("currency", currency)
                        .addValue("occurredAt", occurredAt)
                        .addValue("total", total)
                        .addValue("metadata", toJson(metadata))
                        .addValue("now", now),
                keys, new String[] {"id"});
        return keys.getKey().longValue();
    }

    public void updateHeader(long id, OffsetDateTime occurredAt, java.math.BigDecimal total, JsonNode metadata,
                             OffsetDateTime now) {
        jdbc.update("""
                UPDATE transactions
                SET occurred_at = :occurredAt, total = :total, metadata = :metadata, updated_at = :now
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("occurredAt", occurredAt)
                        .addValue("total", total)
                        .addValue("metadata", toJson(metadata))
                        .addValue("now", now));
    }

    public void replaceTaxLines(long transactionId, List<TaxLine> lines) {
        MapSqlParameterSource id = new MapSqlParameterSource("id", transactionId);
        jdbc.update("DELETE FROM transaction_tax_lines WHERE transaction_id = :id", id);
        MapSqlParameterSource[] batch = new MapSqlParameterSource[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            TaxLine line = lines.get(i);
            batch[i] = new MapSqlParameterSource()
                    .addValue("transactionId", transactionId)
                    .addValue("position", i)
                    .addValue("name", line.name())
                    .addValue("rate", line.rate())
                    .addValue("amount", line.amount());
        }
        jdbc.batchUpdate("""
                INSERT INTO transaction_tax_lines (transaction_id, position, name, rate, amount)
                VALUES (:transactionId, :position, :name, :rate, :amount)
                """, batch);
    }

    public void replaceFeeLines(long transactionId, List<FeeLine> lines) {
        MapSqlParameterSource id = new MapSqlParameterSource("id", transactionId);
        jdbc.update("DELETE FROM transaction_fee_lines WHERE transaction_id = :id", id);
        MapSqlParameterSource[] batch = new MapSqlParameterSource[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            FeeLine line = lines.get(i);
            batch[i] = new MapSqlParameterSource()
                    .addValue("transactionId", transactionId)
                    .addValue("position", i)
                    .addValue("name", line.name())
                    .addValue("amount", line.amount());
        }
        jdbc.batchUpdate("""
                INSERT INTO transaction_fee_lines (transaction_id, position, name, amount)
                VALUES (:transactionId, :position, :name, :amount)
                """, batch);
    }

    /** Deletes the transaction; tax/fee lines go with it (ON DELETE CASCADE). */
    public void delete(long id) {
        jdbc.update("DELETE FROM transactions WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    public Optional<String> findIdByExternalId(String externalId) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM transactions WHERE external_id = :externalId",
                new MapSqlParameterSource("externalId", externalId), Long.class);
        return ids.stream().findFirst().map(Ids::transaction);
    }

    /** True if any invoice line refers to this transaction (uses the unique index on invoice_lines.transaction_id). */
    public boolean isInvoiced(long id) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM invoice_lines WHERE transaction_id = :id",
                new MapSqlParameterSource("id", id), Integer.class);
        return count != null && count > 0;
    }

    public Optional<Transaction> findById(long id) {
        return findByIds(List.of(id), false).stream().findFirst();
    }

    /**
     * Loads full transactions. With forUpdate=true the header rows are locked (SELECT ... FOR UPDATE)
     * until the surrounding DB transaction ends, so concurrent PATCH/DELETE/invoice-create on the
     * same rows are serialised. Rows are locked in id order to avoid deadlocks.
     */
    public List<Transaction> findByIds(Collection<Long> ids, boolean forUpdate) {
        if (ids.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("ids", ids);
        String sql = """
                SELECT id, external_id, type, currency, occurred_at, total, metadata, created_at, updated_at
                FROM transactions WHERE id IN (:ids) ORDER BY id
                """ + (forUpdate ? " FOR UPDATE" : "");
        List<Header> headers = jdbc.query(sql, params, (rs, n) -> readHeader(rs));
        if (headers.isEmpty()) {
            return List.of();
        }
        Map<Long, List<TaxLine>> taxes = new HashMap<>();
        jdbc.query("""
                SELECT transaction_id, name, rate, amount FROM transaction_tax_lines
                WHERE transaction_id IN (:ids) ORDER BY transaction_id, position
                """, params, rs -> {
            taxes.computeIfAbsent(rs.getLong("transaction_id"), k -> new ArrayList<>())
                    .add(new TaxLine(rs.getString("name"), Money.displayRate(rs.getBigDecimal("rate")),
                            rs.getBigDecimal("amount")));
        });
        Map<Long, List<FeeLine>> fees = new HashMap<>();
        jdbc.query("""
                SELECT transaction_id, name, amount FROM transaction_fee_lines
                WHERE transaction_id IN (:ids) ORDER BY transaction_id, position
                """, params, rs -> {
            fees.computeIfAbsent(rs.getLong("transaction_id"), k -> new ArrayList<>())
                    .add(new FeeLine(rs.getString("name"), rs.getBigDecimal("amount")));
        });
        Map<Long, Transaction> byId = new LinkedHashMap<>();
        for (Header h : headers) {
            byId.put(h.id, new Transaction(Ids.transaction(h.id), h.externalId, h.type, h.currency, h.occurredAt,
                    h.total, taxes.getOrDefault(h.id, List.of()), fees.getOrDefault(h.id, List.of()),
                    h.metadata, h.createdAt, h.updatedAt));
        }
        return new ArrayList<>(byId.values());
    }

    /** One page, keyset-paginated: WHERE id > :afterId ORDER BY id LIMIT :limit. Never unbounded. */
    public List<TransactionSummary> list(long afterId, int limit) {
        return jdbc.query("""
                SELECT id, external_id, type, currency, occurred_at, total FROM transactions
                WHERE id > :afterId ORDER BY id LIMIT :limit
                """,
                new MapSqlParameterSource().addValue("afterId", afterId).addValue("limit", limit),
                (rs, n) -> new TransactionSummary(Ids.transaction(rs.getLong("id")), rs.getString("external_id"),
                        rs.getString("type"), rs.getString("currency"), utc(rs, "occurred_at"),
                        rs.getBigDecimal("total")));
    }

    private record Header(long id, String externalId, String type, String currency, OffsetDateTime occurredAt,
                          java.math.BigDecimal total, JsonNode metadata, OffsetDateTime createdAt,
                          OffsetDateTime updatedAt) {}

    private Header readHeader(ResultSet rs) throws SQLException {
        return new Header(rs.getLong("id"), rs.getString("external_id"), rs.getString("type"),
                rs.getString("currency"), utc(rs, "occurred_at"), rs.getBigDecimal("total"),
                fromJson(rs.getString("metadata")), utc(rs, "created_at"), utc(rs, "updated_at"));
    }

    static OffsetDateTime utc(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise metadata", e);
        }
    }

    private JsonNode fromJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored metadata is not valid JSON", e);
        }
    }
}
