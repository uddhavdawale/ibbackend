package org.ib.ibbackend.repository;

import org.ib.ibbackend.entity.Invoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    Page<Invoice> findAll(Pageable pageable);
    Page<Invoice> findByInvoiceNoContainingIgnoreCase(String invoiceNo, Pageable pageable);
}