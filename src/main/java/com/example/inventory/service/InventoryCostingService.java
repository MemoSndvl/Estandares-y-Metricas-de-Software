package com.example.inventory.service;

import com.example.inventory.model.CostingMethod;
import com.example.inventory.model.InventoryMovement;
import com.example.inventory.model.InventoryLedgerRow;
import com.example.inventory.model.InventoryValuation;
import com.example.inventory.model.MovementType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class InventoryCostingService {
    public static final int MONEY_SCALE = 2;
    public static final int UNIT_COST_SCALE = 8;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    public InventoryValuation calculate(
            List<InventoryMovement> movements,
            CostingMethod method) {
        List<InventoryMovement> ordered = new ArrayList<>(movements);
        ordered.sort(Comparator.comparing(InventoryMovement::occurredAt)
                .thenComparingLong(InventoryMovement::id));

        return switch (method) {
            case PEPS -> calculateFifo(ordered);
            case PROMEDIO -> calculateMovingAverage(ordered);
        };
    }

    public List<InventoryLedgerRow> movingAverageLedger(List<InventoryMovement> movements) {
        List<InventoryMovement> ordered = new ArrayList<>(movements);
        ordered.sort(Comparator.comparing(InventoryMovement::occurredAt)
                .thenComparingLong(InventoryMovement::id));

        List<InventoryLedgerRow> rows = new ArrayList<>();
        BigDecimal quantity = ZERO;
        BigDecimal inventoryValue = ZERO;
        BigDecimal average = ZERO;

        for (InventoryMovement movement : ordered) {
            BigDecimal purchaseQuantity = null;
            BigDecimal purchaseUnitCost = null;
            BigDecimal purchaseTotal = null;
            BigDecimal saleQuantity = null;
            BigDecimal saleUnitCost = null;
            BigDecimal saleTotal = null;
            BigDecimal adjustmentIncreaseQuantity = null;
            BigDecimal adjustmentIncreaseUnitCost = null;
            BigDecimal adjustmentIncreaseTotal = null;
            BigDecimal adjustmentDecreaseQuantity = null;
            BigDecimal adjustmentDecreaseUnitCost = null;
            BigDecimal adjustmentDecreaseTotal = null;

            if (movement.increasesStock()) {
                BigDecimal increaseTotal = movement.quantity().multiply(movement.unitCost());
                if (movement.type() == MovementType.ENTRY) {
                    purchaseQuantity = movement.quantity();
                    purchaseUnitCost = movement.unitCost();
                    purchaseTotal = increaseTotal;
                } else {
                    adjustmentIncreaseQuantity = movement.quantity();
                    adjustmentIncreaseUnitCost = movement.unitCost();
                    adjustmentIncreaseTotal = increaseTotal;
                }
                inventoryValue = inventoryValue.add(increaseTotal);
                quantity = quantity.add(movement.quantity());
                average = inventoryValue.divide(quantity, UNIT_COST_SCALE, ROUNDING);
            } else {
                if (movement.quantity().compareTo(quantity) > 0) {
                    throw new IllegalArgumentException(
                            "El historial contiene una salida superior a las existencias.");
                }
                BigDecimal decreaseTotal = average.multiply(movement.quantity());
                if (movement.type() == MovementType.EXIT) {
                    saleQuantity = movement.quantity();
                    saleUnitCost = average;
                    saleTotal = decreaseTotal;
                } else {
                    adjustmentDecreaseQuantity = movement.quantity();
                    adjustmentDecreaseUnitCost = average;
                    adjustmentDecreaseTotal = decreaseTotal;
                }
                quantity = quantity.subtract(movement.quantity());
                inventoryValue = inventoryValue.subtract(decreaseTotal);
                if (quantity.signum() == 0) {
                    inventoryValue = ZERO;
                    average = ZERO;
                }
            }

            rows.add(new InventoryLedgerRow(
                    movement.occurredAt(),
                    purchaseQuantity,
                    purchaseUnitCost,
                    purchaseTotal,
                    saleQuantity,
                    saleUnitCost,
                    saleTotal,
                    adjustmentIncreaseQuantity,
                    adjustmentIncreaseUnitCost,
                    adjustmentIncreaseTotal,
                    adjustmentDecreaseQuantity,
                    adjustmentDecreaseUnitCost,
                    adjustmentDecreaseTotal,
                    quantity,
                    quantity.signum() == 0 ? null : average,
                    inventoryValue));
        }

        return Collections.unmodifiableList(rows);
    }

    private InventoryValuation calculateFifo(List<InventoryMovement> movements) {
        ArrayDeque<Layer> layers = new ArrayDeque<>();
        Map<Long, BigDecimal> issueCosts = new HashMap<>();
        BigDecimal totalQuantity = ZERO;

        for (InventoryMovement movement : movements) {
            if (movement.increasesStock()) {
                layers.addLast(new Layer(movement.quantity(), movement.unitCost()));
                totalQuantity = totalQuantity.add(movement.quantity());
            } else {
                BigDecimal remaining = movement.quantity();
                BigDecimal issueValue = ZERO;
                while (remaining.signum() > 0) {
                    Layer oldest = layers.peekFirst();
                    if (oldest == null) {
                        throw new IllegalArgumentException(
                                "El historial contiene una salida superior a las existencias.");
                    }
                    BigDecimal consumed = remaining.min(oldest.quantity());
                    issueValue = issueValue.add(consumed.multiply(oldest.unitCost()));
                    remaining = remaining.subtract(consumed);
                    totalQuantity = totalQuantity.subtract(consumed);
                    oldest.consume(consumed);
                    if (oldest.quantity().signum() == 0) {
                        layers.removeFirst();
                    }
                }
                issueCosts.put(movement.id(), issueValue.setScale(MONEY_SCALE, ROUNDING));
            }
        }

        BigDecimal rawInventoryValue = ZERO;
        for (Layer layer : layers) {
            rawInventoryValue = rawInventoryValue.add(layer.quantity().multiply(layer.unitCost()));
        }
        BigDecimal inventoryValue = rawInventoryValue.setScale(MONEY_SCALE, ROUNDING);
        BigDecimal unitCost = totalQuantity.signum() == 0
                ? null
                : rawInventoryValue.divide(totalQuantity, UNIT_COST_SCALE, ROUNDING);
        return new InventoryValuation(totalQuantity, inventoryValue, unitCost, issueCosts);
    }

    private InventoryValuation calculateMovingAverage(List<InventoryMovement> movements) {
        Map<Long, BigDecimal> issueCosts = new HashMap<>();
        BigDecimal quantity = ZERO;
        BigDecimal inventoryValue = ZERO;
        BigDecimal average = ZERO;

        for (InventoryMovement movement : movements) {
            if (movement.increasesStock()) {
                BigDecimal entryValue = movement.quantity().multiply(movement.unitCost());
                inventoryValue = inventoryValue.add(entryValue);
                quantity = quantity.add(movement.quantity());
                average = inventoryValue.divide(quantity, UNIT_COST_SCALE, ROUNDING);
            } else {
                if (movement.quantity().compareTo(quantity) > 0) {
                    throw new IllegalArgumentException(
                            "El historial contiene una salida superior a las existencias.");
                }
                BigDecimal issueValue = average.multiply(movement.quantity());
                issueCosts.put(movement.id(), issueValue.setScale(MONEY_SCALE, ROUNDING));
                quantity = quantity.subtract(movement.quantity());
                inventoryValue = inventoryValue.subtract(issueValue);
                if (quantity.signum() == 0) {
                    inventoryValue = ZERO;
                    average = ZERO;
                }
            }
        }

        BigDecimal finalValue = inventoryValue.setScale(MONEY_SCALE, ROUNDING);
        BigDecimal unitCost = quantity.signum() == 0
                ? null
                : average.setScale(UNIT_COST_SCALE, ROUNDING);
        return new InventoryValuation(quantity, finalValue, unitCost, issueCosts);
    }

    private static final class Layer {
        private BigDecimal quantity;
        private final BigDecimal unitCost;

        private Layer(BigDecimal quantity, BigDecimal unitCost) {
            this.quantity = quantity;
            this.unitCost = unitCost;
        }

        private BigDecimal quantity() {
            return quantity;
        }

        private BigDecimal unitCost() {
            return unitCost;
        }

        private void consume(BigDecimal amount) {
            quantity = quantity.subtract(amount);
        }
    }
}
