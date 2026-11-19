package org.ib.ibbackend.repository;

import org.ib.ibbackend.entity.CashCollectionPaymentProof;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CashCollectionPaymentProofRepository
        extends JpaRepository<CashCollectionPaymentProof, Long> {
}
