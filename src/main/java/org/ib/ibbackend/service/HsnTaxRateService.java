package org.ib.ibbackend.service;

import org.ib.ibbackend.entity.HsnTaxRate;
import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.repository.HsnTaxRateRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class HsnTaxRateService {
    private final HsnTaxRateRepository repository;

    public HsnTaxRateService(HsnTaxRateRepository repository) {
        this.repository = repository;
    }

    public Map<String, BigDecimal> ratesForProducts(Collection<Product> products) {
        Set<String> codes = products.stream()
                .map(Product::getHsnSac)
                .map(HsnTaxRateService::normalizeCode)
                .filter(code -> code != null && isValidCode(code))
                .collect(Collectors.toSet());
        if (codes.isEmpty()) {
            return Map.of();
        }

        List<HsnTaxRate> rates = repository.findAllByHsnCodeIn(codes);
        Map<String, BigDecimal> result = new HashMap<>();
        for (HsnTaxRate rate : rates) {
            result.put(rate.getHsnCode(), rate.getGstRate());
        }
        return result;
    }

    public String rateStatus(String hsnCode, Map<String, BigDecimal> rates) {
        String code = normalizeCode(hsnCode);
        if (code == null) {
            return "HSN_MISSING";
        }
        if (!isValidCode(code)) {
            return "HSN_INVALID";
        }
        return rates.containsKey(code) ? "RESOLVED" : "RATE_NOT_FOUND";
    }

    public static String normalizeCode(String hsnCode) {
        if (hsnCode == null || hsnCode.isBlank()) {
            return null;
        }
        return hsnCode.trim();
    }

    public static boolean isValidCode(String hsnCode) {
        return hsnCode != null && hsnCode.matches("[0-9]{4}|[0-9]{6}|[0-9]{8}");
    }
}
