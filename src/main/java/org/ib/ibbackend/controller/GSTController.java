package org.ib.ibbackend.controller;

import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
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
import java.util.concurrent.TimeUnit;

@RestController
@CrossOrigin(originPatterns = "*")
@RequestMapping("/api/gst")
public class GSTController {

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired(required = false)
    private AuditLogService auditLogService;

    private void auditSuccess(String action, String resourceId, String detail) {
        if (auditLogService != null) {
            auditLogService.success(action, resourceId, detail);
        }
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }

        String period = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        BigDecimal totalSales = invoiceRepository.getTotalRevenueByTenantId(tenantId);
        BigDecimal taxCollected = invoiceRepository.findByTenantId(tenantId, org.springframework.data.domain.Pageable.unpaged())
                .getContent().stream()
                .map(Invoice::getGstAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> summary = new HashMap<>();
        summary.put("period", period);
        summary.put("totalSales", totalSales != null ? totalSales.intValue() : 0);
        summary.put("taxCollected", taxCollected.intValue());
        summary.put("returnsFiled", 2);
        summary.put("pendingFiling", 1);
        auditSuccess("EXPORT_DOWNLOAD", "gst-summary", "GST summary fetched");
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore().mustRevalidate().cachePrivate().sMaxAge(0, TimeUnit.SECONDS))
            .header(HttpHeaders.PRAGMA, "no-cache")
            .header(HttpHeaders.EXPIRES, "0")
            .body(summary);
    }

    @GetMapping("/filings")
    public ResponseEntity<List<Map<String, Object>>> getFilings() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }

        List<Map<String, Object>> filings = new ArrayList<>();
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));

        Map<String, Object> filing = new HashMap<>();
        filing.put("id", 1);
        filing.put("month", month);
        filing.put("status", "Filed");
        filing.put("amount", 15000);
        filing.put("submittedAt", LocalDate.now().withDayOfMonth(15).atStartOfDay().toString());
        filings.add(filing);

        auditSuccess("EXPORT_DOWNLOAD", "gst-filings", "GST filings fetched");

        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore().mustRevalidate().cachePrivate().sMaxAge(0, TimeUnit.SECONDS))
            .header(HttpHeaders.PRAGMA, "no-cache")
            .header(HttpHeaders.EXPIRES, "0")
            .body(filings);
    }
}
