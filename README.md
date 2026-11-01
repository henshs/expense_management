# Travel transactions & snapshot invoices (Navan take-home B)

A small Spring Boot service that stores flight, hotel, rail and Navan-fee transactions and
builds frozen (snapshot) invoices from them.

- **Stack:** Java 17+, Spring Boot 3.3, Spring JDBC (`NamedParameterJdbcTemplate`), Flyway, H2 **file-backed** database.
- **Database file:** `./data/billing.mv.db`, created on first start. Schema: `src/main/resources/db/migration/V1__init.sql`.
- **Money:** `DECIMAL(19,2)` in the DB and `BigDecimal` with scale 2 in Java (cents). Tax rates are `DECIMAL(9,6)`. Inputs with more than 2 decimals are rejected (400), never rounded.
- No API keys and no LLM calls at runtime.

Design notes: [ARCHITECTURE.md](ARCHITECTURE.md).

## Requirements

Java 17 or newer (`java -version`). Maven is **not** needed: the bundled wrapper `./mvnw` downloads it.

## Run the API (one command)

```bash
./mvnw spring-boot:run
```

The API listens on `http://localhost:8080`. Health check: `curl localhost:8080/health` → `{"status":"UP"}`.
To start from an empty database, stop the server and delete `./data/`.

## Run the tests (one command)

```bash
./mvnw test
```

The tests start the real application on a random port and call it over HTTP. They use their own
file-backed database under `target/test-db/`. 18 tests cover the 10 required scenarios plus validation,
pagination, invoice PATCH and a concurrent-PATCH "no torn list" check.

| Required scenario | Test |
|---|---|
| 1. Create all types, GET returns coupons / address / legs / fee_kind | `TransactionApiTest.createsAllTypesAndGetReturnsTypeSpecificMetadata` |
| 2. Duplicate `external_id` → 409 with existing id, one row | `TransactionApiTest.duplicateExternalIdReturns409WithExistingIdAndStoresOneRow` |
| 3. Trip invoice totals match `expected-invoice.json` (685.70 EUR) | `InvoiceApiTest.tripInvoiceMatchesExpectedFixture` |
| 4. Snapshot: change stored flight, invoice still shows 220.00 | `InvoiceApiTest.invoiceIsAFrozenSnapshot` |
| 5. Mixed currency → 409 | `InvoiceApiTest.mixedCurrencyIs409` |
| 6. Concurrent duplicate create → one row | `TransactionApiTest.concurrentPostsWithSameExternalIdLeaveExactlyOneRow` (12 threads × 5 rounds) and `databaseUniqueConstraintRejectsSecondRowEvenWithoutTheApi` |
| 7. PATCH invoiced transaction → 409, invoice unchanged | `InvoiceApiTest.patchingInvoicedTransactionIs409AndChangesNothing` |
| 8. DELETE invoiced transaction → 409, row stays | `InvoiceApiTest.deletingInvoicedTransactionIs409AndRowStays` |
| 9. DELETE invoice keeps transactions | `InvoiceApiTest.deletingInvoiceKeepsTransactions` |
| 10. List default 50, `limit=1000` → 400 | `TransactionApiTest.listIsPaginatedWithDefault50AndMax100` |

## API summary

| Method | Path | Success | Errors |
|---|---|---|---|
| POST | `/transactions` | 201 + stored transaction | 400 invalid body / unknown `type` / unknown `fee_kind`; **409 duplicate `external_id`** (body has `existing_id`) |
| GET | `/transactions/{id}` | 200 | 404 |
| GET | `/transactions?limit=&cursor=` | 200 page | 400 if `limit` not in 1..100 |
| PATCH | `/transactions/{id}` | 200 | 400, 404, **409 if on an invoice** |
| DELETE | `/transactions/{id}` | 204 | 404, **409 if on an invoice** |
| POST | `/invoices` | 201 + invoice | 400, 404 missing id, 409 mixed currency / same id twice / already on another invoice |
| GET | `/invoices/{id}` | 200 | 404 |
| GET | `/invoices?limit=&cursor=` | 200 page | 400 |
| PATCH | `/invoices/{id}` | 200 (snapshot rebuilt) | 400, 404, 409 (invoice unchanged) |
| DELETE | `/invoices/{id}` | 204 (transactions kept) | 404 |

Ids look like `txn_1` and `inv_1`. Errors are JSON: `{"error": "<code>", "message": "..."}`.

Documented choices:
- **Duplicate create → 409 Conflict** with `existing_id`. This holds even if the second body differs (identity is `external_id`).
- **Same transaction id twice in one invoice request → 409** (`duplicate_transaction_id`), never double-counted.
- **A transaction can be on at most one invoice** (409 `transaction_already_invoiced`); prevents double billing.
- **Pagination is cursor-based:** a page returns `next_cursor` when there may be more; pass it as `?cursor=`. Default `limit` 50, max 100; `limit` above 100 is **400**.

## Example curls

Run from the project root while the server is running. The request bodies in `requests/` are the
fixture seeds from `fixtures/task-b/seed-payloads.json`, one file per payload. On a fresh database
the ids are `txn_1` … `txn_5` in this order.

### Create (each type)

```bash
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' -d @requests/flight.json
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' -d @requests/hotel.json
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' -d @requests/rail.json
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' -d @requests/trip_fee.json
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' -d @requests/agent_call_fee.json
```

### Duplicate create: POST the same `external_id` twice, still one row

```bash
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' -d @requests/flight.json
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' -d @requests/duplicate_flight.json
# -> 409 {"error":"duplicate_external_id","message":"...","existing_id":"txn_1"}

curl -s 'localhost:8080/transactions?limit=100' | grep -o 'flight-TXL-LHR-2026-03-11' | wc -l
# -> 1
```

### Get / list transactions

```bash
curl -s localhost:8080/transactions/txn_1
curl -s 'localhost:8080/transactions?limit=2'
curl -s 'localhost:8080/transactions?limit=2&cursor=txn_2'
curl -s -o /dev/null -w '%{http_code}\n' 'localhost:8080/transactions?limit=1000'   # -> 400
```

### Create invoice by id (single, and the whole trip)

```bash
curl -s -X POST localhost:8080/invoices -H 'Content-Type: application/json' \
  -d '{"transaction_ids":["txn_1","txn_2","txn_3","txn_4","txn_5"]}'
# -> 201, "total": 685.70, totals per category, tax_rollup, fee_rollup, 5 snapshot lines
```

Single-id form (on a transaction that is not invoiced yet):

```bash
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' -d @requests/flight_usd.json  # txn_6
curl -s -X POST localhost:8080/invoices -H 'Content-Type: application/json' -d '{"transaction_id":"txn_6"}'
```

### Get / list invoices

```bash
curl -s localhost:8080/invoices/inv_1
curl -s 'localhost:8080/invoices?limit=10'
```

### PATCH / DELETE transaction (409 when invoiced)

```bash
# txn_1 is on inv_1, so both are refused and nothing changes:
curl -s -X PATCH localhost:8080/transactions/txn_1 -H 'Content-Type: application/json' -d '{"total": 999.00}'
# -> 409 {"error":"transaction_invoiced",...}
curl -s -X DELETE localhost:8080/transactions/txn_2
# -> 409

# A transaction that is not invoiced can be changed and deleted:
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' \
  -d '{"type":"navan_fee","external_id":"navan-agent-call-extra","currency":"EUR","occurred_at":"2026-03-12T10:00:00Z","total":15.00,"tax_lines":[],"fee_lines":[],"metadata":{"fee_kind":"agent_call_fee"}}'
curl -s -X PATCH localhost:8080/transactions/txn_7 -H 'Content-Type: application/json' \
  -d '{"total": 20.00, "fee_lines":[{"name":"After-hours surcharge","amount":5.00}]}'
curl -s -X DELETE -o /dev/null -w '%{http_code}\n' localhost:8080/transactions/txn_7      # -> 204
```

### PATCH / DELETE invoice

```bash
# Replace the set of transactions; the snapshot is rebuilt from current stored values in one DB transaction.
curl -s -X PATCH localhost:8080/invoices/inv_1 -H 'Content-Type: application/json' \
  -d '{"transaction_ids":["txn_1","txn_2"]}'
# -> 200, total 551.70. txn_3..txn_5 are released and editable again.

# Mixed currency on PATCH -> 409 and the invoice is unchanged (txn_6 is USD):
curl -s -X PATCH localhost:8080/invoices/inv_1 -H 'Content-Type: application/json' \
  -d '{"transaction_ids":["txn_1","txn_6"]}'

# Delete the invoice; its transactions are kept.
curl -s -X DELETE -o /dev/null -w '%{http_code}\n' localhost:8080/invoices/inv_1          # -> 204
curl -s localhost:8080/transactions/txn_1                                                  # still 200
```

## Project layout

```
src/main/java/com/navan/billing/
  common/        errors -> JSON, id format, money scale, pagination params
  transaction/   TransactionController (HTTP) -> TransactionService (rules, duplicate identity)
                 -> TransactionRepository (SQL); metadata/ = typed per-type models + validator
  invoice/       InvoiceController -> InvoiceService (snapshot, locking) -> InvoiceRepository (SQL)
                 InvoiceRollup = pure tax/fee/total arithmetic
src/main/resources/db/migration/V1__init.sql   schema, constraints, indexes
src/test/java/...                                HTTP-level tests
requests/                                        curl bodies (from the fixtures)
```
