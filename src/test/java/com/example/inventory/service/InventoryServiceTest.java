package com.example.inventory.service;

import com.example.inventory.model.CostingMethod;
import com.example.inventory.model.AppUser;
import com.example.inventory.model.InventoryMovement;
import com.example.inventory.model.InventoryValuation;
import com.example.inventory.model.MovementType;
import com.example.inventory.model.Product;
import com.example.inventory.model.ProductDraft;
import com.example.inventory.model.UserRole;
import com.example.inventory.persistence.InventoryRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InventoryServiceTest {
    @Test
    void adjustmentUsesTheCurrentAverageAndRecordsItsAdminAndReason() {
        InMemoryRepository repository = new InMemoryRepository();
        InventoryService service = new InventoryService(repository, new InventoryCostingService());
        AppUser admin = new AppUser(5, "admin", UserRole.ADMIN, true);
        service.registerEntry(1, new BigDecimal("10"), new BigDecimal("6"),
                Instant.parse("2026-01-01T00:00:00Z"));
        service.registerEntry(1, new BigDecimal("10"), new BigDecimal("10"),
                Instant.parse("2026-01-02T00:00:00Z"));

        InventoryValuation increased = service.adjustStock(
                1, new BigDecimal("25"), null, "Conteo físico", Instant.parse("2026-01-03T00:00:00Z"), admin);

        assertEquals(new BigDecimal("25"), increased.quantityOnHand());
        InventoryMovement increase = repository.movements.get(2);
        assertEquals(MovementType.ADJUSTMENT_INCREASE, increase.type());
        assertEquals(new BigDecimal("5"), increase.quantity());
        assertEquals(new BigDecimal("8.0000"), increase.unitCost());
        assertEquals(5L, increase.actorUserId());
        assertEquals("Conteo físico", increase.reason());

        InventoryValuation decreased = service.adjustStock(
                1, new BigDecimal("18"), null, "Producto dañado", Instant.parse("2026-01-04T00:00:00Z"), admin);

        assertEquals(new BigDecimal("18"), decreased.quantityOnHand());
        InventoryMovement reduction = repository.movements.get(3);
        assertEquals(MovementType.ADJUSTMENT_DECREASE, reduction.type());
        assertEquals(new BigDecimal("7"), reduction.quantity());
        assertEquals("Producto dañado", reduction.reason());
        assertEquals(new BigDecimal("56.00"), decreased.issueCosts().get(reduction.id()));
    }

    @Test
    void adjustmentRequiresAdminReasonAndCostWhenNoStockExists() {
        InMemoryRepository repository = new InMemoryRepository();
        InventoryService service = new InventoryService(repository, new InventoryCostingService());
        AppUser admin = new AppUser(5, "admin", UserRole.ADMIN, true);
        AppUser worker = new AppUser(6, "worker", UserRole.TRABAJADOR, true);
        Instant date = Instant.parse("2026-01-01T00:00:00Z");

        assertThrows(IllegalArgumentException.class, () -> service.adjustStock(
                1, BigDecimal.ONE, BigDecimal.ONE, "Conteo", date, worker));
        assertThrows(IllegalArgumentException.class, () -> service.adjustStock(
                1, BigDecimal.ONE, BigDecimal.ONE, " ", date, admin));
        assertThrows(IllegalArgumentException.class, () -> service.adjustStock(
                1, BigDecimal.ONE, null, "Conteo", date, admin));
        assertThrows(IllegalArgumentException.class, () -> service.adjustStock(
                1, BigDecimal.ZERO, null, "Conteo", date, admin));
        assertEquals(0, repository.movements.size());

        InventoryValuation valuation = service.adjustStock(
                1, BigDecimal.ONE, new BigDecimal("2.50"), "Inventario inicial", date, admin);

        assertEquals(BigDecimal.ONE, valuation.quantityOnHand());
        assertEquals(new BigDecimal("2.50"), valuation.inventoryValue());
    }

    @Test
    void rejectedExitDoesNotAddMovementOrChangeStock() {
        InMemoryRepository repository = new InMemoryRepository();
        InventoryService service = new InventoryService(repository, new InventoryCostingService());
        service.registerEntry(1, new BigDecimal("4"), new BigDecimal("2.50"), Instant.parse("2026-01-01T00:00:00Z"));
        int before = repository.movements.size();

        assertThrows(IllegalArgumentException.class, () -> service.registerExit(
                1, new BigDecimal("5"), Instant.parse("2026-01-02T00:00:00Z"), CostingMethod.PEPS));

        assertEquals(before, repository.movements.size());
        assertEquals(new BigDecimal("4"), service.valuation(1, CostingMethod.PEPS).quantityOnHand());
    }

    @Test
    void invalidQuantitiesAndCostsAreRejectedBeforePersistence() {
        InMemoryRepository repository = new InMemoryRepository();
        InventoryService service = new InventoryService(repository, new InventoryCostingService());

        assertThrows(IllegalArgumentException.class, () -> service.registerEntry(
                1, BigDecimal.ZERO, BigDecimal.ONE, Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> service.registerEntry(
                1, new BigDecimal("-1"), BigDecimal.ONE, Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> service.registerEntry(
                1, BigDecimal.ONE, BigDecimal.ZERO, Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> service.registerEntry(
                1, BigDecimal.ONE, new BigDecimal("-1"), Instant.now()));
        assertEquals(0, repository.movements.size());
    }

    @Test
    void rejectsValuesThatWouldBeRoundedByTheDatabase() {
        InMemoryRepository repository = new InMemoryRepository();
        InventoryService service = new InventoryService(repository, new InventoryCostingService());

        assertThrows(IllegalArgumentException.class, () -> service.registerEntry(
                1, new BigDecimal("1.00001"), new BigDecimal("2"), Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> service.registerEntry(
                1, BigDecimal.ONE, new BigDecimal("2.00001"), Instant.now()));
        assertEquals(0, repository.movements.size());
    }

    @Test
    void convertsPackageQuantitiesToPiecesForEntriesAndExits() {
        InMemoryRepository repository = new InMemoryRepository(
                new Product(1, "SKU-BOX", "Caja", null, "caja", "General", true, 12));
        InventoryService service = new InventoryService(repository, new InventoryCostingService());

        InventoryValuation afterEntry = service.registerEntry(
                1, new BigDecimal("2"), new BigDecimal("3.50"), Instant.parse("2026-01-01T00:00:00Z"));

        assertEquals(new BigDecimal("24"), afterEntry.quantityOnHand());
        assertEquals(new BigDecimal("24"), repository.movements.get(0).quantity());
        assertEquals(new BigDecimal("3.50"), repository.movements.get(0).unitCost());

        service.registerExit(1, BigDecimal.ONE, Instant.parse("2026-01-02T00:00:00Z"), CostingMethod.PEPS);
        assertEquals(new BigDecimal("12"), repository.movements.get(1).quantity());
        assertEquals(new BigDecimal("12"), service.valuation(1, CostingMethod.PEPS).quantityOnHand());
        assertThrows(IllegalArgumentException.class, () -> service.registerExit(
                1, new BigDecimal("1.1"), Instant.parse("2026-01-03T00:00:00Z"), CostingMethod.PEPS));
    }

    @Test
    void requiresPackageEquivalenceAndOnlyAllowsItForPackagedUnits() {
        InventoryService service = new InventoryService(
                new InMemoryRepository(), new InventoryCostingService());

        assertThrows(IllegalArgumentException.class, () -> service.createProduct(
                new ProductDraft("BOX-1", "Caja", null, "caja", "General", true, 0)));
        assertThrows(IllegalArgumentException.class, () -> service.createProduct(
                new ProductDraft("KG-1", "Producto", null, "kg", "General", true, 12)));
    }

    private static final class InMemoryRepository implements InventoryRepository {
        private final Product product;
        private final List<InventoryMovement> movements = new ArrayList<>();
        private long nextId = 1;

        private InMemoryRepository() {
            this(new Product(1, "SKU-1", "Producto", null, "unidad", "General", true));
        }

        private InMemoryRepository(Product product) {
            this.product = product;
        }

        @Override
        public Product createProduct(ProductDraft draft) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Product updateProduct(long productId, ProductDraft draft) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Product> findProducts() {
            return List.of(product);
        }

        @Override
        public Optional<Product> findProduct(long productId) {
            return productId == product.id() ? Optional.of(product) : Optional.empty();
        }

        @Override
        public List<InventoryMovement> findMovements(long productId) {
            return List.copyOf(movements);
        }

        @Override
        public <T> T withLockedProduct(long productId, Function<ProductTransaction, T> operation) {
            return operation.apply(new ProductTransaction() {
                @Override
                public Product product() {
                    return product;
                }

                @Override
                public List<InventoryMovement> movements() {
                    return List.copyOf(movements);
                }

                @Override
                public InventoryMovement insertMovement(
                        MovementType type,
                        Instant occurredAt,
                        BigDecimal quantity,
                        BigDecimal unitCost,
                        Long actorUserId,
                        String reason) {
                    InventoryMovement movement = new InventoryMovement(
                            nextId++, productId, type, occurredAt, quantity, unitCost,
                            actorUserId, actorUserId == null ? null : "admin", reason);
                    movements.add(movement);
                    return movement;
                }
            });
        }
    }
}
