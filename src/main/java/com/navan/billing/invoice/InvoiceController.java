package com.navan.billing.invoice;

import com.navan.billing.common.Page;
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

/** HTTP layer only. */
@RestController
@RequestMapping("/invoices")
public class InvoiceController {
    private final InvoiceService service;

    public InvoiceController(InvoiceService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Invoice> create(@RequestBody InvoiceRequest request) {
        Invoice invoice = service.create(request);
        return ResponseEntity.created(URI.create("/invoices/" + invoice.id())).body(invoice);
    }

    @GetMapping("/{id}")
    public Invoice get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping
    public Page<InvoiceSummary> list(@RequestParam(required = false) Integer limit,
                                     @RequestParam(required = false) String cursor) {
        return service.list(limit, cursor);
    }

    @PatchMapping("/{id}")
    public Invoice patch(@PathVariable String id, @RequestBody InvoiceRequest request) {
        return service.patch(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
