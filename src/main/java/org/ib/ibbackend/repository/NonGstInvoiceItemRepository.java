package org.ib.ibbackend.repository;

import org.ib.ibbackend.entity.NonGstInvoiceItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NonGstInvoiceItemRepository extends JpaRepository<NonGstInvoiceItem, Long> {
}
