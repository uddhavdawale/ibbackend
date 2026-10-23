package org.ib.ibbackend.controller;

import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin(originPatterns = "*")
@RequestMapping("/api/gst")
public class GSTController {

    @Autowired
    private InvoiceRepository invoiceRepository;

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        String period = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        BigDecimal totalSales = invoiceRepository.getTotalRevenue();
        BigDecimal taxCollected = invoiceRepository.findAll().stream()
                .map(Invoice::getGstAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> summary = new HashMap<>();
        summary.put("period", period);
        summary.put("totalSales", totalSales != null ? totalSales.intValue() : 0);
        summary.put("taxCollected", taxCollected.intValue());
        summary.put("returnsFiled", 2);
        summary.put("pendingFiling", 1);
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/filings")
    public ResponseEntity<List<Map<String, Object>>> getFilings() {
        List<Map<String, Object>> filings = new ArrayList<>();
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

        Map<String, Object> filing = new HashMap<>();
        filing.put("id", 1);
        filing.put("month", month);
        filing.put("status", "Filed");
        filing.put("amount", 15000);
        filing.put("submittedAt", LocalDate.now().withDayOfMonth(15).atStartOfDay().toString());
        filings.add(filing);

        return ResponseEntity.ok(filings);
    }
}
