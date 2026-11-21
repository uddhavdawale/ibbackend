package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.NonGstInvoiceRequest;
import org.ib.ibbackend.entity.NonGstInvoice;
import org.ib.ibbackend.service.NonGstInvoiceService;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/non-gst-invoices")
public class NonGstInvoiceController {

    private final NonGstInvoiceService nonGstInvoiceService;

    public NonGstInvoiceController(NonGstInvoiceService nonGstInvoiceService) {
        this.nonGstInvoiceService = nonGstInvoiceService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> createNonGstInvoice(@RequestBody NonGstInvoiceRequest request) {
        NonGstInvoice saved = nonGstInvoiceService.createNonGstInvoice(request);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "invoiceNo", saved.getInvoiceNo(),
                "id", saved.getId(),
                "message", "Non-GST invoice saved successfully"
        ));
    }

    @GetMapping
    public ResponseEntity<Page<Map<String, Object>>> listNonGstInvoices(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) String billType
    ) {
        return ResponseEntity.ok(nonGstInvoiceService.listNonGstInvoices(page, size, search, billType));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getNonGstInvoice(@PathVariable Long id) {
        return ResponseEntity.ok(nonGstInvoiceService.getNonGstInvoice(id));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelSellOrder(@PathVariable Long id) {
        return ResponseEntity.ok(nonGstInvoiceService.cancelNonGstInvoice(id));
    }
}
