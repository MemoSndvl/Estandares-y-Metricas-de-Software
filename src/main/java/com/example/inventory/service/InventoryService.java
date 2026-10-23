package com.example.inventory.service;

import com.example.inventory.exception.ProductNotFoundException;
import com.example.inventory.model.CostingMethod;
import com.example.inventory.model.ExitResult;
import com.example.inventory.model.InventoryMovement;
import com.example.inventory.model.InventoryLedgerRow;
import com.example.inventory.model.InventoryValuation;
import com.example.inventory.model.MovementType;
import com.example.inventory.model.Product;
import com.example.inventory.model.ProductDraft;
import com.example.inventory.persistence.InventoryRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


public final class InventoryService {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private final InventoryRepository repository;
    private final InventoryCostingService costingService;

    public InventoryService(InventoryRepository repository, InventoryCostingService costingService) {
        this.repository = repository;
        this.costingService = costingService;
    }

    public Product createProduct(ProductDraft draft) {
        return repository.createProduct(validateDraft(draft));
    }

    public Product updateProduct(long productId, ProductDraft draft) {
        validateProductId(productId);
        Product updated = repository.updateProduct(productId, validateDraft(draft));
        if (updated == null) {
            throw new ProductNotFoundException(productId);
        }
        return updated;
    }

    public List<Product> products() {
        return repository.findProducts();
    }

    public Product product(long productId) {
        validateProductId(productId);
        return repository.findProduct(productId).orElseThrow(() -> new ProductNotFoundException(productId));
    }

        public List<InventoryMovement> history(long productId) {
        validateProductId(productId);
        return repository.findMovementsIfProductExists(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }
        public List<InventoryLedgerRow> movingAverageLedger(long productId) {
        return costingService.movingAverageLedger(history(productId));
    }

    public InventoryValuation valuation(long productId, CostingMethod method) {
        if (method == null) {
            throw new IllegalArgumentException("Debe seleccionar un método de costeo.");
        }
        return costingService.calculate(history(productId), method);
    }
        public record StockRow(Product product, InventoryValuation valuation) {
    }

    public List<StockRow> stockOverview(CostingMethod method) {
        if (method == null) {
            throw new IllegalArgumentException("Debe seleccionar un método de costeo.");
        }
        List<Product> allProducts = repository.findProducts();
        Map<Long, List<InventoryMovement>> movements = repository.findAllMovements();
        List<StockRow> rows = new ArrayList<>(allProducts.size());
        for (Product product : allProducts) {
            rows.add(new StockRow(product, costingService.calculate(
                    movements.getOrDefault(product.id(), List.of()), method)));
        }
        return rows;
    }

    public InventoryValuation valuationOf(List<InventoryMovement> movements, CostingMethod method) {
        return costingService.calculate(movements, method);
    }

    public InventoryValuation registerEntry(
            long productId,
            BigDecimal quantity,
            BigDecimal unitCost,
            Instant occurredAt) {
        validateProductId(productId);
        validatePositive(quantity, "La cantidad de entrada");
        validateNumeric(quantity, 4, 14, "La cantidad");
        validatePositive(unitCost, "El costo unitario");
        validateNumeric(unitCost, 4, 14, "El costo unitario");
        validateDate(occurredAt);
        return repository.withLockedProduct(productId, transaction -> {
            requireActive(transaction.product());
            BigDecimal stockQuantity = toStockQuantity(quantity, transaction.product());
            validateNumeric(stockQuantity, 4, 14, "La cantidad convertida a piezas");
            transaction.insertMovement(MovementType.ENTRY, occurredAt, stockQuantity, unitCost);
            return costingService.calculate(transaction.movements(), CostingMethod.PEPS);
        });
    }

        public ExitResult registerExit(
            long productId,
            BigDecimal quantity,
            Instant occurredAt,
            CostingMethod method) {
        validateProductId(productId);
        validatePositive(quantity, "La cantidad de salida");
        validateNumeric(quantity, 4, 14, "La cantidad");
        validateDate(occurredAt);
        if (method == null) {
            throw new IllegalArgumentException("Debe seleccionar un método de costeo.");
        }
        return repository.withLockedProduct(productId, transaction -> {
            requireActive(transaction.product());
            BigDecimal stockQuantity = toStockQuantity(quantity, transaction.product());
            validateNumeric(stockQuantity, 4, 14, "La cantidad convertida a piezas");
            List<InventoryMovement> existingMovements = transaction.movements();
            InventoryValuation current = costingService.calculate(existingMovements, method);
            if (stockQuantity.compareTo(current.quantityOnHand()) > 0) {
                throw new IllegalArgumentException("La salida supera las existencias disponibles ("
                        + current.quantityOnHand().toPlainString() + ").");
            }
            InventoryMovement movement = transaction.insertMovement(
                    MovementType.EXIT, occurredAt, stockQuantity, null);
            List<InventoryMovement> updated = new ArrayList<>(existingMovements);
            updated.add(movement);
            InventoryValuation valuation = costingService.calculate(updated, method);
            return new ExitResult(movement, valuation.issueCosts().get(movement.id()), valuation);
        });
    }
    private static ProductDraft validateDraft(ProductDraft draft) {
        if (draft == null) {
            throw new IllegalArgumentException("Los datos del producto son obligatorios.");
        }
        String sku = requiredText(draft.sku(), "La clave");
        String name = requiredText(draft.name(), "El nombre");
        String unit = requiredText(draft.unit(), "La unidad");
        String category = requiredText(draft.category(), "La categoría");
        if (draft.piecesPerPackage() < 1) {
            throw new IllegalArgumentException("La cantidad de piezas por empaque debe ser mayor que cero.");
        }
        if (!Product.isPackagedUnit(unit) && draft.piecesPerPackage() != 1) {
            throw new IllegalArgumentException("Solo caja, paquete, bolsa y docena pueden tener equivalencia en piezas.");
        }
        String description = draft.description() == null || draft.description().isBlank()
                ? null
                : draft.description().trim();
        if (sku.length() > 50 || name.length() > 100 || unit.length() > 50 || category.length() > 80
                || (description != null && description.length() > 255)) {
            throw new IllegalArgumentException("Uno o más campos superan la longitud permitida.");
        }
        return new ProductDraft(
                sku, name, description, unit, category, draft.active(), draft.piecesPerPackage());
    }

    private static BigDecimal toStockQuantity(BigDecimal quantity, Product product) {
        return product.packaged()
                ? quantity.multiply(BigDecimal.valueOf(product.piecesPerPackage()))
                : quantity;
    }

    private static String requiredText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " del producto es obligatorio.");
        }
        return value.trim();
    }

    private static void validatePositive(BigDecimal value, String label) {
        if (value == null || value.compareTo(ZERO) <= 0) {
            throw new IllegalArgumentException(label + " debe ser mayor que cero.");
        }
    }

    private static void validateNumeric(
            BigDecimal value,
            int decimalPlaces,
            int integerDigits,
            String label) {
        BigDecimal normalized = value.stripTrailingZeros();
        int actualIntegerDigits = normalized.precision() - normalized.scale();
        if (normalized.scale() > decimalPlaces || actualIntegerDigits > integerDigits) {
            throw new IllegalArgumentException(label + " excede la precisión permitida ("
                    + integerDigits + " enteros y " + decimalPlaces + " decimales).");
        }
    }

    private static void validateDate(Instant occurredAt) {
        if (occurredAt == null) {
            throw new IllegalArgumentException("La fecha del movimiento es obligatoria.");
        }
    }

    private static void validateProductId(long productId) {
        if (productId <= 0) {
            throw new IllegalArgumentException("El ID del producto debe ser mayor que cero.");
        }
    }

    private static void requireActive(Product product) {
        if (!product.active()) {
            throw new IllegalArgumentException("No se permiten movimientos para un producto inactivo.");
        }
    }
}
