package com.navan.billing;

import static com.navan.billing.TransactionApiTest.assertMoney;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InvoiceApiTest extends ApiTestBase {
    private JsonNode expected;
    private String flight;
    private String hotel;
    private String rail;
    private String tripFee;
    private String agentFee;

    @BeforeEach
    void createTrip() throws IOException {
        expected = JSON.readTree(Files.readString(Path.of("fixtures/task-b/expected-invoice.json")));
        flight = create("flight");
        hotel = create("hotel");
        rail = create("rail");
        tripFee = create("trip_fee");
        agentFee = create("agent_call_fee");
    }

    private ObjectNode body(String... ids) {
        ObjectNode body = JSON.createObjectNode();
        ArrayNode array = body.putArray("transaction_ids");
        for (String id : ids) {
            array.add(id);
        }
        return body;
    }

    private String createTripInvoice() {
        Response r = post("/invoices", body(flight, hotel, rail, tripFee, agentFee));
        assertThat(r.status()).isEqualTo(201);
        return r.body().get("id").asText();
    }

    // --- Required test 3: trip invoice totals match expected-invoice.json ------------------------------

    @Test
    void tripInvoiceMatchesExpectedFixture() {
        String id = createTripInvoice();
        JsonNode invoice = get("/invoices/" + id).body();

        assertThat(invoice.get("currency").asText()).isEqualTo(expected.get("currency").asText());
        assertThat(invoice.get("lines").size()).isGreaterThanOrEqualTo(expected.get("line_count_minimum").asInt());
        expected.get("totals").fields().forEachRemaining(e ->
                assertMoney(invoice.get("totals").get(e.getKey()), e.getValue().decimalValue().toPlainString()));
        assertMoney(invoice.get("total"), "685.70");

        // Rollups: same groups and amounts as the fixture examples.
        assertThat(invoice.get("tax_rollup")).hasSize(2);
        assertThat(invoice.at("/tax_rollup/0/name").asText()).isEqualTo("Airport tax");
        assertMoney(invoice.at("/tax_rollup/0/amount"), "15.00");
        assertThat(invoice.at("/tax_rollup/1/name").asText()).isEqualTo("VAT");
        assertThat(invoice.at("/tax_rollup/1/rate").decimalValue()).isEqualByComparingTo("0.07");
        assertMoney(invoice.at("/tax_rollup/1/amount"), "21.70");
        assertThat(invoice.get("fee_rollup")).hasSize(2);
        assertMoney(invoice.at("/fee_rollup/0/amount"), "25.00");
        assertMoney(invoice.at("/fee_rollup/1/amount"), "4.00");

        // Snapshot lines carry the type-specific metadata.
        assertThat(invoice.at("/lines/0/metadata/coupons/0/flight_number").asText()).isEqualTo("BA982");
        assertThat(invoice.at("/lines/1/metadata/address/city").asText()).isEqualTo("London");
        assertThat(invoice.at("/lines/2/metadata/legs")).hasSize(2);
        assertThat(invoice.at("/lines/4/metadata/fee_kind").asText()).isEqualTo("agent_call_fee");
    }

    @Test
    void singleTransactionInvoiceAndMissingOrRepeatedIds() {
        ObjectNode single = JSON.createObjectNode().put("transaction_id", flight);
        Response r = post("/invoices", single);
        assertThat(r.status()).isEqualTo(201);
        assertMoney(r.body().get("total"), "220.00");

        assertThat(post("/invoices", body(hotel, "txn_999999")).status()).isEqualTo(404);
        assertThat(post("/invoices", body(hotel, hotel)).status()).isEqualTo(409); // same id twice: rejected
        assertThat(post("/invoices", body(flight)).status()).isEqualTo(409);       // already billed elsewhere
        assertThat(get("/invoices/inv_999999").status()).isEqualTo(404);
    }

    // --- Required test 4: snapshot is frozen -----------------------------------------------------------

    @Test
    void invoiceIsAFrozenSnapshot() {
        String id = createTripInvoice();

        // Change the stored flight directly in the database (PATCH via the API is refused, see test 7).
        jdbc.update("UPDATE transactions SET total = 999.00 WHERE id = ?", Long.parseLong(flight.substring(4)));
        jdbc.update("UPDATE transaction_tax_lines SET amount = 500.00 WHERE transaction_id = ?",
                Long.parseLong(flight.substring(4)));
        assertMoney(get("/transactions/" + flight).body().get("total"), "999.00");

        JsonNode invoice = get("/invoices/" + id).body();
        assertMoney(invoice.at("/totals/flight"), "220.00");
        assertMoney(invoice.at("/lines/0/total"), "220.00");
        assertMoney(invoice.at("/tax_rollup/0/amount"), "15.00");
        assertMoney(invoice.get("total"), "685.70");
    }

    // --- Required test 5: mixed currency ---------------------------------------------------------------

    @Test
    void mixedCurrencyIs409() {
        ObjectNode usd = seed("flight");
        usd.put("external_id", "flight-usd");
        usd.put("currency", "USD");
        String usdId = post("/transactions", usd).body().get("id").asText();

        Response r = post("/invoices", body(hotel, usdId));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body().get("error").asText()).isEqualTo("mixed_currency");
        assertThat(countRows("SELECT COUNT(*) FROM invoices")).isZero();
    }

    // --- Required test 7: PATCH of an invoiced transaction is 409 -------------------------------------

    @Test
    void patchingInvoicedTransactionIs409AndChangesNothing() {
        String id = createTripInvoice();
        ObjectNode change = JSON.createObjectNode().put("total", new BigDecimal("999.00"));

        Response r = patch("/transactions/" + flight, change);
        assertThat(r.status()).isEqualTo(409);
        assertMoney(get("/transactions/" + flight).body().get("total"), "220.00");
        assertMoney(get("/invoices/" + id).body().get("total"), "685.70");
        assertMoney(get("/invoices/" + id).body().at("/totals/flight"), "220.00");
    }

    // --- Required test 8: DELETE of an invoiced transaction is 409 ------------------------------------

    @Test
    void deletingInvoicedTransactionIs409AndRowStays() {
        createTripInvoice();
        assertThat(delete("/transactions/" + hotel).status()).isEqualTo(409);
        assertThat(get("/transactions/" + hotel).status()).isEqualTo(200);
        assertThat(countRows("SELECT COUNT(*) FROM transaction_tax_lines WHERE transaction_id = ?",
                Long.parseLong(hotel.substring(4)))).isEqualTo(1);
    }

    // --- Required test 9: DELETE invoice keeps the transactions ----------------------------------------

    @Test
    void deletingInvoiceKeepsTransactions() {
        String id = createTripInvoice();
        assertThat(delete("/invoices/" + id).status()).isEqualTo(204);
        assertThat(get("/invoices/" + id).status()).isEqualTo(404);
        for (String txn : List.of(flight, hotel, rail, tripFee, agentFee)) {
            assertThat(get("/transactions/" + txn).status()).isEqualTo(200);
        }
        assertThat(countRows("SELECT COUNT(*) FROM invoice_lines")).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM invoice_line_tax_lines")).isZero();
        // Once un-billed, a transaction can be edited again.
        assertThat(patch("/transactions/" + flight, JSON.createObjectNode().put("total", 230)).status())
                .isEqualTo(200);
        assertThat(delete("/invoices/" + id).status()).isEqualTo(404);
    }

    // --- PATCH invoice ---------------------------------------------------------------------------------

    @Test
    void patchInvoiceRebuildsSnapshotAtomically() {
        Response created = post("/invoices", body(flight, hotel));
        String id = created.body().get("id").asText();
        assertMoney(created.body().get("total"), "551.70");

        // Edit the rail fee while it is NOT on any invoice, then add it to the invoice.
        ObjectNode change = JSON.createObjectNode().put("total", new BigDecimal("100.00"));
        assertThat(patch("/transactions/" + rail, change).status()).isEqualTo(200);

        Response patched = patch("/invoices/" + id, body(flight, rail));
        assertThat(patched.status()).isEqualTo(200);
        assertMoney(patched.body().get("total"), "320.00");
        assertThat(patched.body().get("lines")).hasSize(2);
        assertThat(patch("/transactions/" + hotel, change).status()).isEqualTo(200); // hotel was released

        // Mixed currency on PATCH: 409 and the invoice is unchanged.
        ObjectNode usd = seed("hotel");
        usd.put("external_id", "hotel-usd");
        usd.put("currency", "USD");
        String usdId = post("/transactions", usd).body().get("id").asText();
        assertThat(patch("/invoices/" + id, body(flight, usdId)).status()).isEqualTo(409);
        assertThat(patch("/invoices/" + id, body(flight, "txn_999999")).status()).isEqualTo(404);
        assertThat(patch("/invoices/inv_999999", body(flight)).status()).isEqualTo(404);
        JsonNode after = get("/invoices/" + id).body();
        assertMoney(after.get("total"), "320.00");
        assertThat(after.get("lines")).hasSize(2);
    }

    @Test
    void invoiceListIsPaginated() {
        post("/invoices", body(flight));
        post("/invoices", body(hotel));
        post("/invoices", body(rail));
        Response page = get("/invoices?limit=2");
        assertThat(page.body().get("items")).hasSize(2);
        Response rest = get("/invoices?limit=2&cursor=" + page.body().get("next_cursor").asText());
        assertThat(rest.body().get("items")).hasSize(1);
        assertThat(get("/invoices").body().get("limit").asInt()).isEqualTo(50);
        assertThat(get("/invoices?limit=101").status()).isEqualTo(400);
    }
}
