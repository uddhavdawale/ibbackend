package org.ib.ibbackend.service;

import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import org.ib.ibbackend.dto.InvoiceRequest;
import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.entity.InvoiceItem;
import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.entity.Sequence;
import org.ib.ibbackend.repository.InvoiceItemRepository;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.repository.ProductRepository;
import org.ib.ibbackend.repository.SequenceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.stream.Collectors;
import java.math.RoundingMode;  // For divide()
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;

@Service
@Transactional
public class InvoiceService {
    @Autowired private InvoiceRepository invoiceRepo;
    @Autowired private InvoiceItemRepository itemRepo;
    @Autowired private ProductRepository productRepo;
    @Autowired private SequenceRepository seqRepo;

    public Invoice createInvoice(InvoiceRequest req) {
        // 1. Generate invoiceNo
        String year = String.valueOf(Year.now().getValue());
        Sequence seq = getNextSequence("invoice");
        String invoiceNo = String.format("INV-%s/%04d", year, seq.getValue());

                // 2-4. Rest unchanged...
                for (InvoiceItem item : req.getItems()) {
                    Product p = productRepo.findById(item.getProductId())
                            .orElseThrow(() -> new EntityNotFoundException("Product not found: " + item.getProductId()));
                    if (p.getQuantity() < item.getQty()) {
                        throw new RuntimeException("Insufficient stock for " + item.getName());
                    }
                    p.setQuantity(p.getQuantity() - item.getQty());
                    productRepo.save(p);
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

            private Sequence getNextSequence(String seqName) {
                Sequence seq = seqRepo.findByNameForUpdate(seqName);  // ✅ Locked repo method

                if (seq == null) {
                    seq = new Sequence(seqName);
                    seqRepo.save(seq);
                } else {
                    seq.nextValue();  // Increment
                    seqRepo.save(seq);
                }
                return seq;
            }



    }