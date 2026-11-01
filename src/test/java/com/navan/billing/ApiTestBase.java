package com.navan.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Starts the real application on a random port and talks to it over HTTP,
 * exactly like a client would. Every test starts from an empty database.
 */
// Tests use their own file-backed H2 database under target/ (a real on-disk DB, not in-memory).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties =
        "spring.datasource.url=jdbc:h2:file:./target/test-db/billing;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000")
public abstract class ApiTestBase {
    protected static final ObjectMapper JSON = new ObjectMapper();
    protected static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    protected JsonNode seeds;

    public record Response(int status, JsonNode body) {}

    @BeforeEach
    void resetDatabaseAndLoadSeeds() throws IOException {
        for (String table : new String[] {"invoice_line_tax_lines", "invoice_line_fee_lines", "invoice_lines",
                "invoices", "transaction_tax_lines", "transaction_fee_lines", "transactions"}) {
            jdbc.update("DELETE FROM " + table);
        }
        seeds = JSON.readTree(Files.readString(Path.of("fixtures/task-b/seed-payloads.json")));
    }

    /** A copy of one seed payload, safe to modify. */
    protected ObjectNode seed(String name) {
        return seeds.get(name).deepCopy();
    }

    protected Response post(String path, Object body) { return send("POST", path, body); }
    protected Response patch(String path, Object body) { return send("PATCH", path, body); }
    protected Response get(String path) { return send("GET", path, null); }
    protected Response delete(String path) { return send("DELETE", path, null); }

    /** POSTs a seed and returns its new id; fails the test if it was not created. */
    protected String create(String seedName) {
        Response r = post("/transactions", seed(seedName));
        if (r.status() != 201) {
            throw new AssertionError("Expected 201 creating " + seedName + " but got " + r.status() + ": " + r.body());
        }
        return r.body().get("id").asText();
    }

    protected int countRows(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    protected Response send(String method, String path, Object body) {
        try {
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : JSON.writeValueAsString(body));
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json")
                    .method(method, publisher)
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = response.body().isEmpty() ? null : JSON.readTree(response.body());
            return new Response(response.statusCode(), json);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
