package com.example.inventory.model;

import java.math.BigDecimal;

public record ExitResult(
        InventoryMovement movement,
        BigDecimal issueCost,
        InventoryValuation valuation) {
}
