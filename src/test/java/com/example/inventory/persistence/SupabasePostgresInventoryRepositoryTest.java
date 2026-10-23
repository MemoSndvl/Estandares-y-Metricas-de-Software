package com.example.inventory.persistence;

import com.example.inventory.model.CostingMethod;
import com.example.inventory.model.InventoryValuation;
import com.example.inventory.model.Product;
import com.example.inventory.model.ProductDraft;
import com.example.inventory.exception.InventoryPersistenceException;
import com.example.inventory.exception.ProductNotFoundException;
import com.example.inventory.service.InventoryCostingService;
import com.example.inventory.service.InventoryService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SupabasePostgresInventoryRepositoryTest {
    @Test
    void storesMovementsAndReadsThemBackThroughANewServiceInstance() throws Exception {
        JdbcDataSource dataSource = database("persistence");
        createSchema(dataSource);
        InventoryService service = service(dataSource);
        Product product = service.createProduct(new ProductDraft(
                "SUP-1", "Producto de integración", null, "unidad", "Alimentos", true));

        service.registerEntry(product.id(), new BigDecimal("10"), new BigDecimal("20"),
                Instant.parse("2026-01-01T00:00:00Z"));
        service.registerEntry(product.id(), new BigDecimal("10"), new BigDecimal("30"),
                Instant.parse("2026-01-02T00:00:00Z"));
        var exit = service.registerExit(product.id(), new BigDecimal("5"),
                Instant.parse("2026-01-03T00:00:00Z"), CostingMethod.PROMEDIO);

        InventoryService restarted = service(dataSource);
        InventoryValuation valuation = restarted.valuation(product.id(), CostingMethod.PROMEDIO);
        InventoryValuation fifo = restarted.valuation(product.id(), CostingMethod.PEPS);
        var ledger = restarted.movingAverageLedger(product.id());

        assertEquals(3, restarted.history(product.id()).size());
        assertEquals("Alimentos", restarted.product(product.id()).category());
        assertEquals(new BigDecimal("15.0000"), ledger.get(2).balanceQuantity());
        assertEquals(0, new BigDecimal("375").compareTo(ledger.get(2).balanceTotal()));
        assertEquals(new BigDecimal("125.00"), exit.issueCost());
        assertEquals(new BigDecimal("15.0000"), valuation.quantityOnHand());
        assertEquals(new BigDecimal("375.00"), valuation.inventoryValue());
        assertEquals(new BigDecimal("400.00"), fifo.inventoryValue());
        assertEquals(new BigDecimal("100.00"), fifo.issueCosts().get(exit.movement().id()));
        assertExitCostPlaceholder(dataSource, exit.movement().id());
    }

    @Test
    void productCanBeUpdatedAndInactiveProductsRejectMovements() throws Exception {
        JdbcDataSource dataSource = database("product-update");
        createSchema(dataSource);
        InventoryService service = service(dataSource);
        Product created = service.createProduct(new ProductDraft(
                "UP-1", "Producto anterior", null, "unidad", "Alimentos", true));

        Product updated = service.updateProduct(created.id(), new ProductDraft(
                "UP-2", "Producto actualizado", "Descripción", "caja", "Empaque", false));

        assertEquals(updated, service.product(created.id()));
        assertEquals("UP-2", updated.sku());
        assertEquals("Empaque", updated.category());
        assertThrows(IllegalArgumentException.class, () -> service.registerEntry(
                created.id(), BigDecimal.ONE, BigDecimal.ONE, Instant.parse("2026-01-01T00:00:00Z")));
        assertEquals(0, service.history(created.id()).size());
    }

    @Test
    void insufficientExitLeavesDatabaseUnchanged() throws Exception {
        JdbcDataSource dataSource = database("rollback");
        createSchema(dataSource);
        InventoryService service = service(dataSource);
        Product product = service.createProduct(new ProductDraft(
                "ROLL-1", "Producto transaccional", null, "unidad", "General", true));
        service.registerEntry(product.id(), new BigDecimal("4"), new BigDecimal("2"),
                Instant.parse("2026-01-01T00:00:00Z"));

        assertThrows(IllegalArgumentException.class, () -> service.registerExit(
                product.id(), new BigDecimal("5"), Instant.parse("2026-01-02T00:00:00Z"),
                CostingMethod.PEPS));

        assertEquals(1, service.history(product.id()).size());
        assertEquals(new BigDecimal("4.0000"),
                service.valuation(product.id(), CostingMethod.PEPS).quantityOnHand());
    }

    @Test
    void recalculationFailureAfterInsertRollsBackTheTransaction() throws Exception {
        JdbcDataSource dataSource = database("chronological-rollback");
        createSchema(dataSource);
        InventoryService service = service(dataSource);
        Product product = service.createProduct(new ProductDraft(
                "DATE-1", "Producto con fechas", null, "unidad", "General", true));
        service.registerEntry(product.id(), new BigDecimal("5"), new BigDecimal("10"),
                Instant.parse("2026-01-02T00:00:00Z"));

        assertThrows(IllegalArgumentException.class, () -> service.registerExit(
                product.id(), new BigDecimal("2"), Instant.parse("2026-01-01T00:00:00Z"),
                CostingMethod.PEPS));

        assertEquals(1, service.history(product.id()).size());
        assertEquals(new BigDecimal("5.0000"),
                service.valuation(product.id(), CostingMethod.PEPS).quantityOnHand());
    }

    @Test
    void nonexistentProductDoesNotCreateMovements() throws Exception {
        JdbcDataSource dataSource = database("missing-product");
        createSchema(dataSource);
        InventoryService service = service(dataSource);

        assertThrows(ProductNotFoundException.class, () -> service.history(999));
        assertThrows(IllegalArgumentException.class, () -> service.registerEntry(
                999, BigDecimal.ONE, BigDecimal.ONE, Instant.parse("2026-01-01T00:00:00Z")));
    }

    @Test
    void rejectsDuplicateProductKeyAndKeepsTheOriginalProduct() throws Exception {
        JdbcDataSource dataSource = database("unique-product");
        createSchema(dataSource);
        InventoryService service = service(dataSource);
        service.createProduct(new ProductDraft("DUP-1", "Original", null, "unidad", "General", true));

        assertThrows(InventoryPersistenceException.class, () -> service.createProduct(
                new ProductDraft("DUP-1", "Duplicado", null, "unidad", "General", true)));
        assertEquals("Original", service.products().get(0).name());
    }

    @Test
    void persistsThePackagePieceEquivalence() throws Exception {
        JdbcDataSource dataSource = database("package-equivalence");
        createSchema(dataSource);
        InventoryService service = service(dataSource);

        Product created = service.createProduct(
                new ProductDraft("BOX-12", "Caja de 12", null, "caja", "General", true, 12));

        assertEquals(12, created.piecesPerPackage());
        assertEquals(12, service.product(created.id()).piecesPerPackage());
        assertEquals("pza", service.product(created.id()).stockUnit());
    }

    private static InventoryService service(JdbcDataSource dataSource) {
        return new InventoryService(
                new SupabasePostgresInventoryRepository(dataSource),
                new InventoryCostingService());
    }

    private static JdbcDataSource database(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    private static void createSchema(JdbcDataSource dataSource) throws Exception {
        String[] statements = {
                """
                CREATE TABLE Productos (
                    Id INT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    Clave VARCHAR(50) NOT NULL UNIQUE,
                    Nombre VARCHAR(100) NOT NULL,
                    Descripcion VARCHAR(255),
                    UnidadMedida VARCHAR(50) NOT NULL,
                    Categoria VARCHAR(80) NOT NULL DEFAULT 'General',
                    PiezasPorEmpaque INT NOT NULL DEFAULT 1,
                    Estado BOOLEAN NOT NULL DEFAULT TRUE
                )
                """,
                """
                CREATE TABLE Movimientos (
                    Id INT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    ProductoId INT NOT NULL,
                    TipoMovimiento VARCHAR(10) NOT NULL
                        CHECK (TipoMovimiento IN ('ENTRADA', 'SALIDA')),
                    Fecha TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    Cantidad NUMERIC(18,4) NOT NULL CHECK (Cantidad > 0),
                    CostoUnitario NUMERIC(18,4) NOT NULL CHECK (CostoUnitario >= 0),
                    CostoTotal NUMERIC(18,4)
                        GENERATED ALWAYS AS (Cantidad * CostoUnitario),
                    CONSTRAINT FK_Movimientos_Productos
                        FOREIGN KEY (ProductoId) REFERENCES Productos(Id)
                )
                """
        };
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static void assertExitCostPlaceholder(JdbcDataSource dataSource, long movementId)
            throws Exception {
        String sql = "SELECT CostoUnitario, CostoTotal FROM Movimientos WHERE Id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, movementId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                assertEquals(new BigDecimal("0.0000"), result.getBigDecimal("CostoUnitario"));
                assertEquals(new BigDecimal("0.0000"), result.getBigDecimal("CostoTotal"));
            }
        }
    }
}
