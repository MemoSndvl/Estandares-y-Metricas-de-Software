package com.example.inventory.persistence;

import com.example.inventory.model.InventoryMovement;
import com.example.inventory.model.MovementType;
import com.example.inventory.model.Product;
import com.example.inventory.model.ProductDraft;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.HashMap;
import java.util.Map;


public interface InventoryRepository {
    Product createProduct(ProductDraft draft);

    Product updateProduct(long productId, ProductDraft draft);

    List<Product> findProducts();

    Optional<Product> findProduct(long productId);

    List<InventoryMovement> findMovements(long productId);
        default Optional<List<InventoryMovement>> findMovementsIfProductExists(long productId) {
        return findProduct(productId).map(product -> findMovements(product.id()));
    }

        default Map<Long, List<InventoryMovement>> findAllMovements() {
        Map<Long, List<InventoryMovement>> result = new HashMap<>();
        for (Product product : findProducts()) {
            result.put(product.id(), findMovements(product.id()));
        }
        return result;
    }
    <T> T withLockedProduct(long productId, Function<ProductTransaction, T> operation);

    interface ProductTransaction {
        Product product();

        List<InventoryMovement> movements();

        InventoryMovement insertMovement(
                MovementType type,
                Instant occurredAt,
                BigDecimal quantity,
                BigDecimal unitCost,
                Long actorUserId,
                String reason);
    }
}
