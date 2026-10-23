package com.example.inventory.model;

import java.math.BigDecimal;
import java.time.Instant;

public record InventoryLedgerRow(
        Instant occurredAt,
        BigDecimal purchaseQuantity,
        BigDecimal purchaseUnitCost,
        BigDecimal purchaseTotal,
        BigDecimal saleQuantity,
        BigDecimal saleUnitCost,
        BigDecimal saleTotal,
        BigDecimal balanceQuantity,
        BigDecimal balanceUnitCost,
        BigDecimal balanceTotal) {
}
