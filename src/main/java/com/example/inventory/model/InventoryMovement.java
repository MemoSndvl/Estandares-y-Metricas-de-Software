package com.example.inventory.model;

import java.math.BigDecimal;
import java.time.Instant;

public record InventoryMovement(
        long id,
        long productId,
        MovementType type,
        Instant occurredAt,
        BigDecimal quantity,
        BigDecimal unitCost) {
}
