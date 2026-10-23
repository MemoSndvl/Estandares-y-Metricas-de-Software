package com.example.inventory.service;

import com.example.inventory.model.CostingMethod;
import com.example.inventory.model.InventoryMovement;
import com.example.inventory.model.InventoryValuation;
import com.example.inventory.model.MovementType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InventoryCostingServiceTest {
    private final InventoryCostingService service = new InventoryCostingService();

    @Test
    void referenceScenarioProducesExpectedFifoAndMovingAverageResults() {
        List<InventoryMovement> movements = List.of(
                movement(1, MovementType.ENTRY, "2026-01-01T00:00:00Z", "10", "20"),
                movement(2, MovementType.ENTRY, "2026-01-02T00:00:00Z", "10", "30"),
                movement(3, MovementType.EXIT, "2026-01-03T00:00:00Z", "5", null));

        InventoryValuation fifo = service.calculate(movements, CostingMethod.PEPS);
        InventoryValuation average = service.calculate(movements, CostingMethod.PROMEDIO);

        assertEquals(new BigDecimal("15"), fifo.quantityOnHand());
        assertEquals(new BigDecimal("100.00"), fifo.issueCosts().get(3L));
        assertEquals(new BigDecimal("400.00"), fifo.inventoryValue());
        assertEquals(new BigDecimal("15"), average.quantityOnHand());
        assertEquals(new BigDecimal("125.00"), average.issueCosts().get(3L));
        assertEquals(new BigDecimal("375.00"), average.inventoryValue());
    }

    @Test
    void fifoExitConsumesMultipleOldestLayers() {
        List<InventoryMovement> movements = List.of(
                movement(1, MovementType.ENTRY, "2026-01-01T00:00:00Z", "3", "10"),
                movement(2, MovementType.ENTRY, "2026-01-02T00:00:00Z", "4", "12"),
                movement(3, MovementType.EXIT, "2026-01-03T00:00:00Z", "5", null));

        InventoryValuation valuation = service.calculate(movements, CostingMethod.PEPS);

        assertEquals(new BigDecimal("54.00"), valuation.issueCosts().get(3L));
        assertEquals(new BigDecimal("2"), valuation.quantityOnHand());
        assertEquals(new BigDecimal("24.00"), valuation.inventoryValue());
    }

    @Test
    void movingAverageLedgerKeepsRunningPurchaseSaleAndBalanceValues() {
        List<InventoryMovement> movements = List.of(
                movement(1, MovementType.ENTRY, "2026-01-01T00:00:00Z", "10", "20"),
                movement(2, MovementType.ENTRY, "2026-01-02T00:00:00Z", "10", "30"),
                movement(3, MovementType.EXIT, "2026-01-03T00:00:00Z", "5", null),
                movement(4, MovementType.ENTRY, "2026-01-04T00:00:00Z", "10", "10"));

        var ledger = service.movingAverageLedger(movements);

        assertEquals(4, ledger.size());
        assertEquals(new BigDecimal("25.00000000"), ledger.get(2).saleUnitCost());
        assertEquals(new BigDecimal("125.00000000"), ledger.get(2).saleTotal());
        assertEquals(new BigDecimal("15"), ledger.get(2).balanceQuantity());
        assertEquals(new BigDecimal("375.00000000"), ledger.get(2).balanceTotal());
        assertEquals(new BigDecimal("25"), ledger.get(3).balanceQuantity());
        assertEquals(new BigDecimal("19.00000000"), ledger.get(3).balanceUnitCost());
        assertEquals(new BigDecimal("475.00000000"), ledger.get(3).balanceTotal());
    }

    private static InventoryMovement movement(
            long id, MovementType type, String instant, String quantity, String unitCost) {
        return new InventoryMovement(id, 1, type, Instant.parse(instant),
                new BigDecimal(quantity), unitCost == null ? null : new BigDecimal(unitCost));
    }
}
