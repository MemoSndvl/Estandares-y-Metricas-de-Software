package com.example.inventory.model;

import java.math.BigDecimal;
import java.util.Map;

public record InventoryValuation(
        BigDecimal quantityOnHand,
        BigDecimal inventoryValue,
        BigDecimal unitCost,
        Map<Long, BigDecimal> issueCosts) {

    public InventoryValuation {
        issueCosts = Map.copyOf(issueCosts);
    }
}
