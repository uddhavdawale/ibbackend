package org.ib.ibbackend.repository;

import org.ib.ibbackend.entity.HsnTaxRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface HsnTaxRateRepository extends JpaRepository<HsnTaxRate, String> {
    Optional<HsnTaxRate> findByHsnCode(String hsnCode);

    List<HsnTaxRate> findAllByHsnCodeIn(Collection<String> hsnCodes);
}
