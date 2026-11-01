package com.navan.billing.transaction;

import com.navan.billing.common.Page;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP layer only: parse the request, call the service, choose the status code. */
@RestController
@RequestMapping("/transactions")
public class TransactionController {
    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Transaction> create(@Valid @RequestBody CreateTransactionRequest request) {
        Transaction created = service.create(request);
        return ResponseEntity.created(URI.create("/transactions/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public Transaction get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping
    public Page<TransactionSummary> list(@RequestParam(required = false) Integer limit,
                                         @RequestParam(required = false) String cursor) {
        return service.list(limit, cursor);
    }

    @PatchMapping("/{id}")
    public Transaction patch(@PathVariable String id, @Valid @RequestBody PatchTransactionRequest request) {
        return service.patch(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
