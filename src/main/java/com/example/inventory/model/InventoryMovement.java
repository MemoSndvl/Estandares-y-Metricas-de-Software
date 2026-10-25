package com.example.inventory.model;

import java.math.BigDecimal;
import java.time.Instant;

public record InventoryMovement(
        long id,
        long productId,
        MovementType type,
        Instant occurredAt,
        BigDecimal quantity,
        BigDecimal unitCost,
        Long actorUserId,
        String actorUsername,
        String reason) {
    public InventoryMovement(
            long id,
            long productId,
            MovementType type,
            Instant occurredAt,
            BigDecimal quantity,
            BigDecimal unitCost) {
        this(id, productId, type, occurredAt, quantity, unitCost, null, null, null);
    }

    public boolean increasesStock() {
        return type == MovementType.ENTRY || type == MovementType.ADJUSTMENT_INCREASE;
    }

    public boolean decreasesStock() {
        return type == MovementType.EXIT || type == MovementType.ADJUSTMENT_DECREASE;
    }
}
