package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.ProductUpsertRequest;
import org.ib.ibbackend.entity.InventoryMovement;
import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.ProductRepository;
import org.ib.ibbackend.service.InventorySecurityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private InventorySecurityService inventorySecurityService;

    @InjectMocks
    private ProductController productController;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void shouldApplyManualProductQuantityOnlyOnce() {
        TenantContext.setCurrentTenant(1L);

        ProductUpsertRequest request = new ProductUpsertRequest();
        request.setName("Manual product");
        request.setSku("MANUAL-1");
        request.setQuantity(7);

        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product product = invocation.getArgument(0);
            product.setId(42L);
            return product;
        });
        Product stockedProduct = new Product();
        stockedProduct.setId(42L);
        stockedProduct.setQuantity(7);
        when(inventorySecurityService.applyMovement(
                eq(42L),
                eq(7),
                eq(InventoryMovement.MovementType.ADJUSTMENT),
                eq("PRODUCT_CREATE"),
                eq("42"),
                eq("Initial stock set during product creation"),
                eq(false)
        )).thenReturn(stockedProduct);

        ResponseEntity<?> response = productController.addProduct(request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(7, ((Product) response.getBody()).getQuantity());
        verify(productRepository).save(org.mockito.ArgumentMatchers.argThat(
                product -> product.getQuantity() == 0
        ));
        verify(inventorySecurityService).applyMovement(
                42L,
                7,
                InventoryMovement.MovementType.ADJUSTMENT,
                "PRODUCT_CREATE",
                "42",
                "Initial stock set during product creation",
                false
        );
    }
}
