package org.ib.ibbackend.service;

import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import org.ib.ibbackend.dto.InvoiceRequest;
import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.entity.InvoiceItem;
import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.repository.InvoiceItemRepository;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.repository.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.stream.Collectors;
import java.math.RoundingMode;  // For divide()
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.LocalDate;

@Service
@Transactional
public class InvoiceService {
    @Autowired private InvoiceRepository invoiceRepo;
    @Autowired private InvoiceItemRepository itemRepo;
    @Autowired private ProductRepository productRepo;
    @Autowired(required = false) private InventorySecurityService inventorySecurityService;

    public Invoice createInvoice(InvoiceRequest req) {
        String invoiceNo = InvoiceNumberGenerator.generate();

                // 2-4. Rest unchanged...
                for (InvoiceItem item : req.getItems()) {
                    if (inventorySecurityService != null) {
                        inventorySecurityService.applyMovement(
                                item.getProductId(),
                                -item.getQty(),
                                org.ib.ibbackend.entity.InventoryMovement.MovementType.SALE,
                                "INVOICE",
                                invoiceNo,
                                "Stock deducted for GST invoice sale",
                                false
                        );
                    } else {
                        Product p = productRepo.findById(item.getProductId())
                                .orElseThrow(() -> new EntityNotFoundException("Product not found: " + item.getProductId()));
                        if (p.getQuantity() < item.getQty()) {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient stock for " + item.getName());
                        }
                        p.setQuantity(p.getQuantity() - item.getQty());
                        productRepo.save(p);
                    }
                }

        BigDecimal subtotal = req.getItems().stream()
                .map(item -> item.getPrice().multiply(BigDecimal.valueOf(item.getQty())))
                .collect(Collectors.reducing(BigDecimal.ZERO, BigDecimal::add));

        BigDecimal gstAmount = req.getItems().stream()
                .map(item -> item.getPrice()
                        .multiply(BigDecimal.valueOf(item.getQty()))
                        .multiply(new BigDecimal(item.getGstRate()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)))
                .collect(Collectors.reducing(BigDecimal.ZERO, BigDecimal::add));

        BigDecimal grandTotal = subtotal.add(gstAmount);


        Invoice invoice = new Invoice(invoiceNo, req.getCustomerName(), req.getCustomerPhone(),
                LocalDate.now(), req.getPaymentMethod(), subtotal, gstAmount, grandTotal, req.getItems());
        return invoiceRepo.save(invoice);
            }
    }