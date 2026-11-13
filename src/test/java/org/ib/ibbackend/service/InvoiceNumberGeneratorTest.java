package org.ib.ibbackend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InvoiceNumberGeneratorTest {

    @Test
    void shouldGenerateTimestampedInvoiceNumbersWithFourDigitSuffix() {
        for (int i = 0; i < 1_000; i++) {
            String invoiceNumber = InvoiceNumberGenerator.generate();
            assertTrue(invoiceNumber.matches("INV-\\d{12}-\\d{4}"));
        }
    }
}
