package org.ib.ibbackend.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.security.SecureRandom;
import java.util.Locale;

public final class InvoiceNumberGenerator {
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private InvoiceNumberGenerator() {
    }

    public static String generate() {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String randomSuffix = String.format(Locale.ROOT, "%04d", RANDOM.nextInt(10_000));
        return "INV-" + timestamp + "-" + randomSuffix;
    }
}
