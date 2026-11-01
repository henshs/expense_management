package com.navan.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class TransactionApiTest extends ApiTestBase {

    // --- Required test 1: create every type; GET round-trips the type-specific structure ---------------

    @Test
    void createsAllTypesAndGetReturnsTypeSpecificMetadata() {
        String flight = create("flight");
        String hotel = create("hotel");
        String rail = create("rail");
        String tripFee = create("trip_fee");
        String agentFee = create("agent_call_fee");

        JsonNode f = get("/transactions/" + flight).body();
        assertThat(f.get("type").asText()).isEqualTo("flight");
        assertThat(f.at("/metadata/coupons/0/flight_number").asText()).isEqualTo("BA982");
        assertThat(f.at("/metadata/origin").asText()).isEqualTo("TXL");
        assertThat(f.at("/metadata/destination").asText()).isEqualTo("LHR");
        assertThat(f.at("/tax_lines/0/name").asText()).isEqualTo("Airport tax");
        assertThat(f.at("/fee_lines/0/name").asText()).isEqualTo("YQ");
        assertMoney(f.get("total"), "220.00");

        JsonNode h = get("/transactions/" + hotel).body();
        assertThat(h.at("/metadata/name").asText()).isEqualTo("Hotel Mitte London");
        assertThat(h.at("/metadata/address/street").asText()).isEqualTo("12 Kingsway");
        assertThat(h.at("/metadata/address/city").asText()).isEqualTo("London");
        assertThat(h.at("/metadata/address/country").asText()).isEqualTo("GB");
        assertThat(h.at("/tax_lines/0/rate").decimalValue()).isEqualByComparingTo("0.07");

        JsonNode r = get("/transactions/" + rail).body();
        assertThat(r.at("/metadata/legs")).hasSize(2);
        assertThat(r.at("/metadata/legs/1/origin").asText()).isEqualTo("Lille Europe");
        assertThat(r.at("/metadata/legs/0/class").asText()).isEqualTo("standard");

        assertThat(get("/transactions/" + tripFee).body().at("/metadata/fee_kind").asText()).isEqualTo("trip_fee");
        assertThat(get("/transactions/" + agentFee).body().at("/metadata/fee_kind").asText()).isEqualTo("agent_call_fee");

        // Taxes and fees really are rows in their own tables.
        assertThat(countRows("SELECT COUNT(*) FROM transaction_tax_lines")).isEqualTo(2);
        assertThat(countRows("SELECT COUNT(*) FROM transaction_fee_lines")).isEqualTo(2);
    }

    @Test
    void getUnknownTransactionIs404() {
        assertThat(get("/transactions/txn_999999").status()).isEqualTo(404);
        assertThat(get("/transactions/not-an-id").status()).isEqualTo(404);
    }

    @Test
    void rejectsUnknownTypeUnknownFeeKindAndBadMetadata() {
        ObjectNode unknownType = seed("flight");
        unknownType.put("type", "cruise");
        assertThat(post("/transactions", unknownType).status()).isEqualTo(400);

        ObjectNode unknownKind = seed("trip_fee");
        ((ObjectNode) unknownKind.get("metadata")).put("fee_kind", "coffee_fee");
        assertThat(post("/transactions", unknownKind).status()).isEqualTo(400);

        ObjectNode emptyMetadata = seed("flight");
        emptyMetadata.putObject("metadata");
        assertThat(post("/transactions", emptyMetadata).status()).isEqualTo(400);

        ObjectNode flightAsHotel = seed("hotel");
        flightAsHotel.put("type", "flight");
        assertThat(post("/transactions", flightAsHotel).status()).isEqualTo(400);

        ObjectNode railWithoutLegs = seed("rail");
        ((ObjectNode) railWithoutLegs.get("metadata")).putArray("legs");
        assertThat(post("/transactions", railWithoutLegs).status()).isEqualTo(400);

        ObjectNode tooPrecise = seed("flight");
        tooPrecise.put("total", new BigDecimal("220.001"));
        assertThat(post("/transactions", tooPrecise).status()).isEqualTo(400);

        assertThat(countRows("SELECT COUNT(*) FROM transactions")).isZero();
    }

    // --- Required test 2: duplicate external_id ------------------------------------------------------

    @Test
    void duplicateExternalIdReturns409WithExistingIdAndStoresOneRow() {
        String id = create("flight");

        Response same = post("/transactions", seed("flight"));
        assertThat(same.status()).isEqualTo(409);
        assertThat(same.body().get("existing_id").asText()).isEqualTo(id);

        Response fixtureDuplicate = post("/transactions", seed("duplicate_flight"));
        assertThat(fixtureDuplicate.status()).isEqualTo(409);
        assertThat(fixtureDuplicate.body().get("existing_id").asText()).isEqualTo(id);

        assertThat(countRows("SELECT COUNT(*) FROM transactions WHERE external_id = ?",
                "flight-TXL-LHR-2026-03-11")).isEqualTo(1);
    }

    // --- Required test 6: concurrent duplicate create ------------------------------------------------

    @Test
    void concurrentPostsWithSameExternalIdLeaveExactlyOneRow() throws Exception {
        int threads = 12;
        for (int round = 0; round < 5; round++) {
            ObjectNode body = seed("flight");
            body.put("external_id", "race-" + round);
            CyclicBarrier startTogether = new CyclicBarrier(threads);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<Response>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Response> call = () -> {
                    startTogether.await();
                    return post("/transactions", body);
                };
                results.add(pool.submit(call));
            }
            List<Integer> statuses = new ArrayList<>();
            String createdId = null;
            List<String> reportedExisting = new ArrayList<>();
            for (Future<Response> f : results) {
                Response r = f.get();
                statuses.add(r.status());
                if (r.status() == 201) {
                    createdId = r.body().get("id").asText();
                } else if (r.status() == 409) {
                    reportedExisting.add(r.body().get("existing_id").asText());
                }
            }
            pool.shutdown();

            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
            assertThat(reportedExisting).allMatch(createdId::equals);
            assertThat(countRows("SELECT COUNT(*) FROM transactions WHERE external_id = ?", "race-" + round))
                    .isEqualTo(1);
        }
    }

    @Test
    void databaseUniqueConstraintRejectsSecondRowEvenWithoutTheApi() {
        create("flight");
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DuplicateKeyException.class, () ->
                jdbc.update("""
                        INSERT INTO transactions (external_id, type, currency, occurred_at, total, metadata, created_at, updated_at)
                        VALUES ('flight-TXL-LHR-2026-03-11', 'flight', 'EUR', CURRENT_TIMESTAMP, 1.00, '{}',
                                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """));
    }

    // --- PATCH / DELETE on transactions that are NOT invoiced ---------------------------------------

    @Test
    void patchReplacesFieldsAndDeleteRemovesRowAndLines() {
        String id = create("flight");
        ObjectNode change = JSON.createObjectNode();
        change.put("total", new BigDecimal("230.00"));
        change.putArray("tax_lines").addObject().put("name", "Airport tax").put("amount", new BigDecimal("25.00"));
        Response patched = patch("/transactions/" + id, change);
        assertThat(patched.status()).isEqualTo(200);
        assertMoney(patched.body().get("total"), "230.00");
        assertMoney(patched.body().at("/tax_lines/0/amount"), "25.00");
        assertThat(patched.body().at("/fee_lines/0/name").asText()).isEqualTo("YQ"); // untouched

        ObjectNode immutable = JSON.createObjectNode().put("currency", "USD");
        assertThat(patch("/transactions/" + id, immutable).status()).isEqualTo(400);
        assertThat(patch("/transactions/txn_999999", change).status()).isEqualTo(404);

        assertThat(delete("/transactions/" + id).status()).isEqualTo(204);
        assertThat(get("/transactions/" + id).status()).isEqualTo(404);
        assertThat(countRows("SELECT COUNT(*) FROM transaction_tax_lines")).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM transaction_fee_lines")).isZero();
        assertThat(delete("/transactions/" + id).status()).isEqualTo(404);
    }

    @Test
    void concurrentPatchesNeverTearTheTaxLineList() throws Exception {
        String id = create("flight");
        int threads = 8;
        CyclicBarrier startTogether = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Response>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            results.add(pool.submit(() -> {
                ObjectNode change = JSON.createObjectNode();
                var taxes = change.putArray("tax_lines");
                for (int k = 0; k < 3; k++) {
                    taxes.addObject().put("name", "writer-" + n).put("amount", new BigDecimal(n + ".00"));
                }
                startTogether.await();
                return patch("/transactions/" + id, change);
            }));
        }
        for (Future<Response> f : results) {
            assertThat(f.get().status()).isEqualTo(200);
        }
        pool.shutdown();

        JsonNode taxes = get("/transactions/" + id).body().get("tax_lines");
        assertThat(taxes).hasSize(3);
        String winner = taxes.get(0).get("name").asText();
        assertThat(taxes).allMatch(t -> t.get("name").asText().equals(winner)); // one writer's full list, not a mix
    }

    // --- Required test 10: bounded lists -------------------------------------------------------------

    @Test
    void listIsPaginatedWithDefault50AndMax100() {
        for (int i = 0; i < 55; i++) {
            ObjectNode body = seed("trip_fee");
            body.put("external_id", "fee-" + i);
            assertThat(post("/transactions", body).status()).isEqualTo(201);
        }

        Response first = get("/transactions");
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("limit").asInt()).isEqualTo(50);
        assertThat(first.body().get("items")).hasSize(50);
        String cursor = first.body().get("next_cursor").asText();

        Response second = get("/transactions?cursor=" + cursor);
        assertThat(second.body().get("items")).hasSize(5);
        assertThat(second.body().has("next_cursor")).isFalse();

        assertThat(get("/transactions?limit=1000").status()).isEqualTo(400);
        assertThat(get("/transactions?limit=0").status()).isEqualTo(400);
        assertThat(get("/transactions?limit=100").body().get("items")).hasSize(55);
    }

    static void assertMoney(JsonNode node, String expected) {
        assertThat(node.decimalValue()).isEqualByComparingTo(expected);
    }
}
