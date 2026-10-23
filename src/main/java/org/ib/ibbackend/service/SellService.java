package org.ib.ibbackend.service;



import org.ib.ibbackend.repository.SalesRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class SellService {

    @Autowired
    private SalesRepository salesRepository;

    public List<Map<String, Object>> getLastSixMonthsSales() {
        List<Map<String, Object>> result = new ArrayList<>();

        for (int i = 5; i >= 0; i--) {
            LocalDate monthStart = LocalDate.now().minusMonths(i).withDayOfMonth(1);
            BigDecimal sales = salesRepository.sumSalesByMonth(monthStart.getYear(), monthStart.getMonthValue());

            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("month", monthStart.format(DateTimeFormatter.ofPattern("MMM").withLocale(java.util.Locale.ENGLISH)).substring(0, 3));
            data.put("sales", (sales != null ? sales : BigDecimal.ZERO).intValue());
            result.add(data);
        }
        return result;
    }
}