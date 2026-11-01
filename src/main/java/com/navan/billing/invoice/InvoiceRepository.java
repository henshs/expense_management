package com.navan.billing.invoice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navan.billing.common.Ids;
import com.navan.billing.common.Money;
import com.navan.billing.transaction.FeeLine;
import com.navan.billing.transaction.TaxLine;
import com.navan.billing.transaction.Transaction;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** All SQL for invoices and their snapshot tables. Callers own the DB transaction. */
@Repository
public class InvoiceRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public InvoiceRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public record Header(long id, String currency, BigDecimal total, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    public long insertHeader(String currency, BigDecimal total, OffsetDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO invoices (currency, total, created_at, updated_at) VALUES (:currency, :total, :now, :now)
                """,
                new MapSqlParameterSource().addValue("currency", currency).addValue("total", total).addValue("now", now),
                keys, new String[] {"id"});
        return keys.getKey().longValue();
    }

    public void updateHeader(long id, String currency, BigDecimal total, OffsetDateTime now) {
        jdbc.update("UPDATE invoices SET currency = :currency, total = :total, updated_at = :now WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("currency", currency)
                        .addValue("total", total).addValue("now", now));
    }

    /** Locks the invoice row until the DB transaction ends. Empty if it does not exist. */
    public Optional<Header> lockHeader(long id) {
        return jdbc.query("SELECT id, currency, total, created_at, updated_at FROM invoices WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id), (rs, n) -> readHeader(rs)).stream().findFirst();
    }

    public Optional<Header> findHeader(long id) {
        return jdbc.query("SELECT id, currency, total, created_at, updated_at FROM invoices WHERE id = :id",
                new MapSqlParameterSource("id", id), (rs, n) -> readHeader(rs)).stream().findFirst();
    }

    /** Deletes the invoice; snapshot lines and their tax/fee copies cascade. Transactions are untouched. */
    public void delete(long id) {
        jdbc.update("DELETE FROM invoices WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    public void deleteLines(long invoiceId) {
        jdbc.update("DELETE FROM invoice_lines WHERE invoice_id = :id", new MapSqlParameterSource("id", invoiceId));
    }

    /** Ids of the given transactions that are already billed on an invoice other than excludeInvoiceId. */
    public List<String> findBilledElsewhere(Collection<Long> transactionIds, long excludeInvoiceId) {
        return jdbc.queryForList("""
                SELECT transaction_id FROM invoice_lines
                WHERE transaction_id IN (:ids) AND invoice_id <> :exclude ORDER BY transaction_id
                """,
                new MapSqlParameterSource().addValue("ids", transactionIds).addValue("exclude", excludeInvoiceId),
                Long.class).stream().map(Ids::transaction).toList();
    }

    /** Writes the snapshot: a COPY of every field of each transaction, including its tax and fee lines. */
    public void insertSnapshot(long invoiceId, List<Transaction> transactions) {
        for (int position = 0; position < transactions.size(); position++) {
            Transaction t = transactions.get(position);
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update("""
                    INSERT INTO invoice_lines
                        (invoice_id, position, transaction_id, external_id, type, currency, occurred_at, total, metadata)
                    VALUES (:invoiceId, :position, :transactionId, :externalId, :type, :currency, :occurredAt, :total, :metadata)
                    """,
                    new MapSqlParameterSource()
                            .addValue("invoiceId", invoiceId)
                            .addValue("position", position)
                            .addValue("transactionId", Ids.parse(t.id(), Ids.TRANSACTION_PREFIX))
                            .addValue("externalId", t.externalId())
                            .addValue("type", t.type())
                            .addValue("currency", t.currency())
                            .addValue("occurredAt", t.occurredAt())
                            .addValue("total", t.total())
                            .addValue("metadata", toJson(t.metadata())),
                    keys, new String[] {"id"});
            long lineId = keys.getKey().longValue();

            List<TaxLine> taxes = t.taxLines();
            MapSqlParameterSource[] taxBatch = new MapSqlParameterSource[taxes.size()];
            for (int i = 0; i < taxes.size(); i++) {
                taxBatch[i] = new MapSqlParameterSource().addValue("lineId", lineId).addValue("position", i)
                        .addValue("name", taxes.get(i).name()).addValue("rate", taxes.get(i).rate())
                        .addValue("amount", taxes.get(i).amount());
            }
            jdbc.batchUpdate("""
                    INSERT INTO invoice_line_tax_lines (invoice_line_id, position, name, rate, amount)
                    VALUES (:lineId, :position, :name, :rate, :amount)
                    """, taxBatch);

            List<FeeLine> fees = t.feeLines();
            MapSqlParameterSource[] feeBatch = new MapSqlParameterSource[fees.size()];
            for (int i = 0; i < fees.size(); i++) {
                feeBatch[i] = new MapSqlParameterSource().addValue("lineId", lineId).addValue("position", i)
                        .addValue("name", fees.get(i).name()).addValue("amount", fees.get(i).amount());
            }
            jdbc.batchUpdate("""
                    INSERT INTO invoice_line_fee_lines (invoice_line_id, position, name, amount)
                    VALUES (:lineId, :position, :name, :amount)
                    """, feeBatch);
        }
    }

    /** Reads the snapshot lines of one invoice. Reads ONLY snapshot tables, never the live transactions table. */
    public List<InvoiceLine> findLines(long invoiceId) {
        MapSqlParameterSource params = new MapSqlParameterSource("invoiceId", invoiceId);
        Map<Long, List<TaxLine>> taxes = new HashMap<>();
        jdbc.query("""
                SELECT t.invoice_line_id, t.name, t.rate, t.amount
                FROM invoice_line_tax_lines t JOIN invoice_lines l ON l.id = t.invoice_line_id
                WHERE l.invoice_id = :invoiceId ORDER BY t.invoice_line_id, t.position
                """, params, rs -> {
            taxes.computeIfAbsent(rs.getLong("invoice_line_id"), k -> new ArrayList<>())
                    .add(new TaxLine(rs.getString("name"), Money.displayRate(rs.getBigDecimal("rate")),
                            rs.getBigDecimal("amount")));
        });
        Map<Long, List<FeeLine>> fees = new HashMap<>();
        jdbc.query("""
                SELECT f.invoice_line_id, f.name, f.amount
                FROM invoice_line_fee_lines f JOIN invoice_lines l ON l.id = f.invoice_line_id
                WHERE l.invoice_id = :invoiceId ORDER BY f.invoice_line_id, f.position
                """, params, rs -> {
            fees.computeIfAbsent(rs.getLong("invoice_line_id"), k -> new ArrayList<>())
                    .add(new FeeLine(rs.getString("name"), rs.getBigDecimal("amount")));
        });
        return jdbc.query("""
                SELECT id, transaction_id, external_id, type, currency, occurred_at, total, metadata
                FROM invoice_lines WHERE invoice_id = :invoiceId ORDER BY position
                """, params, (rs, n) -> {
            long lineId = rs.getLong("id");
            return new InvoiceLine(Ids.transaction(rs.getLong("transaction_id")), rs.getString("external_id"),
                    rs.getString("type"), rs.getString("currency"), utc(rs, "occurred_at"),
                    rs.getBigDecimal("total"), taxes.getOrDefault(lineId, List.of()),
                    fees.getOrDefault(lineId, List.of()), fromJson(rs.getString("metadata")));
        });
    }

    /** One page, keyset-paginated. The line count is a correlated COUNT on the indexed invoice_id. */
    public List<InvoiceSummary> list(long afterId, int limit) {
        return jdbc.query("""
                SELECT i.id, i.currency, i.total, i.created_at,
                       (SELECT COUNT(*) FROM invoice_lines l WHERE l.invoice_id = i.id) AS line_count
                FROM invoices i WHERE i.id > :afterId ORDER BY i.id LIMIT :limit
                """,
                new MapSqlParameterSource().addValue("afterId", afterId).addValue("limit", limit),
                (rs, n) -> new InvoiceSummary(Ids.invoice(rs.getLong("id")), rs.getString("currency"),
                        rs.getBigDecimal("total"), rs.getInt("line_count"), utc(rs, "created_at")));
    }

    private static Header readHeader(ResultSet rs) throws SQLException {
        return new Header(rs.getLong("id"), rs.getString("currency"), rs.getBigDecimal("total"),
                utc(rs, "created_at"), utc(rs, "updated_at"));
    }

    private static OffsetDateTime utc(ResultSet rs, String column) throws SQLException {
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
