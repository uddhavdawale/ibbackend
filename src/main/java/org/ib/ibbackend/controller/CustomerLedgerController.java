package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.CustomerLedgerResponse;
import org.ib.ibbackend.service.CustomerLedgerService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDate;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/customer-ledger")
public class CustomerLedgerController {
    private final CustomerLedgerService customerLedgerService;

    public CustomerLedgerController(CustomerLedgerService customerLedgerService) {
        this.customerLedgerService = customerLedgerService;
    }

    @GetMapping
    public CustomerLedgerResponse getLedger(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String customerKey,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return customerLedgerService.getLedger(page, size, search, customerKey, from, to);
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String customerKey,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Integer tenantId = customerLedgerService.requireAuthorizedTenantId();
        StreamingResponseBody stream = output ->
                customerLedgerService.writeCsv(tenantId, search, customerKey, from, to, output);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment().filename("customer-ledger.csv").build());
        headers.setCacheControl("no-store");
        headers.setPragma("no-cache");
        return ResponseEntity.ok().headers(headers).body(stream);
    }
}
