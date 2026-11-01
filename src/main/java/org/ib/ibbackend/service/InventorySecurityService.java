package org.ib.ibbackend.service;

import org.ib.ibbackend.entity.InventoryMovement;
import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.InventoryMovementRepository;
import org.ib.ibbackend.repository.ProductRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InventorySecurityService {

    private final ProductRepository productRepository;
    private final InventoryMovementRepository movementRepository;
    private final UserRepository userRepository;

    public InventorySecurityService(ProductRepository productRepository,
                                    InventoryMovementRepository movementRepository,
                                    UserRepository userRepository) {
        this.productRepository = productRepository;
        this.movementRepository = movementRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Product applyMovement(Long productId,
                                 int deltaQty,
                                 InventoryMovement.MovementType movementType,
                                 String referenceType,
                                 String referenceId,
                                 String note,
                                 boolean requireApproval) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        if (requireApproval) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Approval required for this inventory movement type");
        }

        Product product = productRepository.findByIdAndTenantIdForUpdate(productId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Product not found for tenant: " + productId));

        int beforeQty = product.getQuantity() == null ? 0 : product.getQuantity();
        int afterQty = beforeQty + deltaQty;
        if (afterQty < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Insufficient stock for product: " + product.getName());
        }

        product.setQuantity(afterQty);
        Product saved = productRepository.save(product);

        InventoryMovement movement = new InventoryMovement();
        movement.setProduct(saved);
        movement.setTenant(saved.getTenant());
        movement.setUser(resolveCurrentUser());
        movement.setMovementType(movementType);
        movement.setDeltaQty(deltaQty);
        movement.setBeforeQty(beforeQty);
        movement.setAfterQty(afterQty);
        movement.setReferenceType(referenceType);
        movement.setReferenceId(referenceId);
        movement.setNote(note);
        movementRepository.save(movement);

        return saved;
    }

    private User resolveCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getName()
                : null;

        if (email == null || email.isBlank()) {
            return null;
        }

        return userRepository.findByEmail(email).orElse(null);
    }
}
