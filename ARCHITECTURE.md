# Architecture

## Layers (seams)

```
HTTP            TransactionController / InvoiceController   parse JSON, pick status codes
Rules           TransactionService                          validation, duplicate identity, "billed = frozen"
                InvoiceService                              snapshot build, currency rule, locking
                MetadataValidator + typed models            flight / hotel / rail / navan_fee shapes
                InvoiceRollup                               pure arithmetic (tax/fee rollups, totals)
Persistence     TransactionRepository / InvoiceRepository   SQL only, no business rules
Database        H2 file DB, schema in V1__init.sql (Flyway)  constraints are the final guarantee
```

Plain JDBC (`NamedParameterJdbcTemplate`) instead of JPA/Hibernate, so every query, lock and
constraint is visible in the code and none are generated behind the scenes.

## Tables and models

| Table | Holds |
|---|---|
| `transactions` | Shared header for **all** types: `id`, `external_id` (UNIQUE), `type` (CHECK in 4 values), `currency`, `occurred_at`, `total DECIMAL(19,2)`, `metadata` (JSON text), timestamps |
| `transaction_tax_lines` | Government taxes, one row each: `name`, optional `rate DECIMAL(9,6)`, `amount`, `position` |
| `transaction_fee_lines` | Carrier/merchant fees, one row each: `name`, `amount`, `position` |
| `invoices` | Invoice header: `currency`, `total` |
| `invoice_lines` | **Snapshot copy** of each billed transaction (type, external_id, currency, occurred_at, total, metadata) + `transaction_id` (UNIQUE) |
| `invoice_line_tax_lines` / `invoice_line_fee_lines` | Snapshot copies of the tax and fee lines |

**Taxes vs fees:** two separate tables, not an "extras" column. VAT (with a rate) and a YQ surcharge are
different things for accounting, and they roll up separately on the invoice.

**Type-specific data:** one shared header table plus a JSON `metadata` column. Every type has a typed Java model
that the JSON must satisfy before it is stored; anything else is a 400:

- `FlightMetadata`: `origin`, `destination`, `fare`, and `coupons[]` (each with `flight_number`, optional `carrier`, `from`, `to`, `departure`, `arrival`). At least one coupon.
- `HotelMetadata`: `name`, `address{street, city, country}`, `check_in` < `check_out`, `nights`, `room_rate_per_night`, optional `extras[]`.
- `RailMetadata`: `legs[]` (each with `origin`, `destination`, `departure`, `arrival`, optional `train_number`, `class`). A change of train is two legs.
- `NavanFeeMetadata`: `fee_kind` ∈ {`trip_fee`, `agent_call_fee`} (enum: unknown kind → 400), optional `description`, `related_external_id`.

Why JSON + typed validation rather than a child table per type: the brief allows it, and money, identity and
billing (the things we query, constrain and sum) are real columns and tables. The per-type details are
read back whole and are not aggregated. Adding a type means one model class, not a migration. Trade-off: the DB
cannot query inside metadata efficiently. On Postgres this column would be `JSONB`, and any field that
became a filter (e.g. `fee_kind`, flight number) would be promoted to its own indexed column or child
table (`flight_coupons`, `rail_legs`).

`navan_fee` is a normal transaction type next to flight/hotel/rail, with its own `external_id`, total and lines,
so a trip fee or agent call fee is billed and rolled up like any other charge.

## `external_id` uniqueness and concurrent POSTs

1. `transactions.external_id` has a **UNIQUE constraint**. This is the guarantee: the database will never hold two rows.
2. Fast path: the service first looks up `external_id` (indexed). If it exists → **409** with `existing_id`.
3. Race path: two overlapping POSTs can both pass step 2. Both try to INSERT; the database lets exactly one commit, and
   the other gets a unique-violation (`DuplicateKeyException`). The service catches it, re-reads the winner's id and returns **409**
   with `existing_id`. The test `concurrentPostsWithSameExternalIdLeaveExactlyOneRow` fires 12 simultaneous POSTs ×5 rounds and
   confirms exactly one 201, eleven 409s and one row (logs show the race path being hit).
4. Header + tax lines + fee lines are inserted in **one DB transaction**: all or nothing.

No in-memory map, counter or lock: ids come from the DB identity column.

## Invoice snapshot

Creating an invoice **copies** every field of each transaction (including tax and fee lines and metadata) into the
`invoice_line*` tables. `GET /invoices/{id}` reads **only** those tables and never joins back to `transactions`, so
editing a transaction later cannot change an issued invoice. Rollups are computed from the snapshot rows, so they are
frozen too.

- Totals: `invoice.total` = sum of line totals (BigDecimal). `totals` also breaks it down by `flight` / `hotel` / `rail` /
  and Navan fees by `fee_kind` (`trip_fee`, `agent_call_fee`), plus `grand_total`.
- **Tax rollup** groups by `(name, rate)`, so VAT 7 % and VAT 19 % stay separate. **Fee rollup** groups by `name`.
  Order = first appearance.
- Mixed currencies → 409. The same id twice in one request → 409. A transaction already on another invoice → 409
  (UNIQUE on `invoice_lines.transaction_id`: one transaction is billed at most once).

**Keeping billed data frozen:** PATCH/DELETE of a transaction that is on an invoice → 409, nothing written.
`invoice_lines.transaction_id` is a foreign key with `ON DELETE RESTRICT`, so even a bug in the service could not delete a
billed transaction. Deleting an invoice cascades to its snapshot rows only; transactions stay (and become editable again).

**Race between "PATCH transaction" and "create invoice":** both start with `SELECT ... FOR UPDATE` on the transaction row(s).
They are therefore serialised: either the PATCH commits first and the invoice snapshots the new values, or the invoice
commits first and the PATCH then sees it and returns 409. Rows are always locked in id order to avoid deadlocks.

**PATCH invoice:** lock the invoice row, lock the new transactions, validate (404 / 409), delete the old snapshot rows,
write the new snapshot, update the header, all in one DB transaction. Any error rolls back, so the old snapshot stays intact.

## Concurrent PATCH / DELETE on the same id

Every write locks its target row first (`FOR UPDATE`) inside a DB transaction, and replaces tax/fee lists as a whole
(delete + insert) inside the same transaction. Concurrent writers queue on the lock; the result is last-write-wins but
always one writer's complete list. The test `concurrentPatchesNeverTearTheTaxLineList` checks this with 8 parallel PATCHes.

## Money

`BigDecimal`, scale 2, `DECIMAL(19,2)` in the DB (rates: scale 6, `DECIMAL(9,6)`). JSON numbers are parsed straight into
`BigDecimal` (never via `double`). More than 2 decimal places → 400 rather than silent rounding. Negative amounts → 400
(refunds/credit notes are out of scope).

## High volume

**What breaks first, and what is in place:**

1. **Duplicate POSTs under load.** Check-then-insert alone would race and create two rows. → UNIQUE constraint on
   `external_id` + catch-and-409. Correct under any concurrency without Redis or a lock service.
2. **Large lists.** `SELECT *` without a bound would read the whole table into memory and time out. → Every list query has
   `LIMIT` (default 50, max 100, larger → 400). Pagination is **keyset** (`WHERE id > :cursor ORDER BY id LIMIT :n`) on the
   primary-key index, so page 10 000 costs the same as page 1. `OFFSET` would scan and discard all skipped rows. Nothing is
   paginated in application memory. List rows are summaries (no lines/metadata) to keep pages small.
3. **Hot rows under concurrent PATCH.** Writers to the *same* transaction queue on its row lock (by design: correctness
   over throughput for one booking). Writers to *different* rows do not block each other, since locks are per-row, not per table.
   A lock held longer than `LOCK_TIMEOUT` (10 s) returns 503 so the client can retry, instead of hanging.
4. **"Is this transaction invoiced?"** runs on every PATCH/DELETE. → It is a lookup on the UNIQUE index on
   `invoice_lines.transaction_id`, not a scan.

**Indexes:** PK on `transactions.id` and `invoices.id` (GET by id); UNIQUE `transactions.external_id`; UNIQUE
`invoice_lines.transaction_id`; `(transaction_id, position)` on tax/fee lines; `(invoice_id, position)` on invoice lines;
`invoice_line_id` on snapshot tax/fee lines. All are declared in `V1__init.sql`.

**Next steps at real scale (not built):** move to Postgres (H2 is a single-file embedded DB: fine here, not for many app
instances), `JSONB` metadata, a connection pool sized to the DB, and the cursor encoded as an opaque token.
