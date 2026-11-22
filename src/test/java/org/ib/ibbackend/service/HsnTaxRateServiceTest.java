package org.ib.ibbackend.service;

import org.ib.ibbackend.entity.HsnTaxRate;
import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.repository.HsnTaxRateRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HsnTaxRateServiceTest {
    @Test
    void resolvesValidCodesInOneBatchAndReportsUnresolvedStates() {
        HsnTaxRateRepository repository = mock(HsnTaxRateRepository.class);
        HsnTaxRate rate = new HsnTaxRate();
        rate.setHsnCode("1234");
        rate.setGstRate(new BigDecimal("18.00"));
        when(repository.findAllByHsnCodeIn(anyCollection())).thenReturn(List.of(rate));
        HsnTaxRateService service = new HsnTaxRateService(repository);

        Product resolved = product(" 1234 ");
        Product invalid = product("SKU-2");
        Product missing = product(null);
        List<Product> products = List.of(resolved, invalid, missing);
        Map<String, BigDecimal> rates = service.ratesForProducts(products);

        assertThat(rates).containsEntry("1234", new BigDecimal("18.00"));
        assertThat(service.rateStatus(resolved.getHsnSac(), rates)).isEqualTo("RESOLVED");
        assertThat(service.rateStatus(invalid.getHsnSac(), rates)).isEqualTo("HSN_INVALID");
        assertThat(service.rateStatus(missing.getHsnSac(), rates)).isEqualTo("HSN_MISSING");
        verify(repository).findAllByHsnCodeIn(org.mockito.ArgumentMatchers.<Collection<String>>argThat(
                codes -> codes.contains("1234") && codes.size() == 1));
    }

    private Product product(String hsn) {
        Product product = new Product();
        product.setHsnSac(hsn);
        return product;
    }
}
