package com.example.inventory.persistence;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import javax.sql.DataSource;

import com.example.inventory.exception.InventoryPersistenceException;
import com.example.inventory.model.InventoryMovement;
import com.example.inventory.model.MovementType;
import com.example.inventory.model.Product;
import com.example.inventory.model.ProductDraft;

public final class SupabasePostgresInventoryRepository implements InventoryRepository {
    private final DataSource dataSource;

    public SupabasePostgresInventoryRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Product createProduct(ProductDraft draft) {
        String sql = """
                INSERT INTO productos
                    (Clave, Nombre, Descripcion, UnidadMedida, Categoria, Estado, PiezasPorEmpaque)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            setDraft(statement, draft);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("La base de datos no devolvió el ID del producto.");
                }
                return new Product(keys.getLong(1), draft.sku(), draft.name(),
                        draft.description(), draft.unit(), draft.category(), draft.active(),
                        draft.piecesPerPackage());
            }
        } catch (SQLException exception) {
            throw persistenceFailure("No se pudo guardar el producto.", exception);
        }
    }

    @Override
    public Product updateProduct(long productId, ProductDraft draft) {
        String sql = """
                UPDATE productos
                SET Clave = ?, Nombre = ?, Descripcion = ?, UnidadMedida = ?, Categoria = ?, Estado = ?,
                    PiezasPorEmpaque = ?
                WHERE Id = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            setDraft(statement, draft);
            statement.setLong(8, productId);
            if (statement.executeUpdate() == 0) {
                return null;
            }
            return new Product(productId, draft.sku(), draft.name(),
                    draft.description(), draft.unit(), draft.category(), draft.active(),
                    draft.piecesPerPackage());
        } catch (SQLException exception) {
            throw persistenceFailure("No se pudo actualizar el producto.", exception);
        }
    }

    @Override
    public List<Product> findProducts() {
        String sql = """
                SELECT Id, Clave, Nombre, Descripcion, UnidadMedida, Categoria, Estado, PiezasPorEmpaque
                FROM productos ORDER BY Id
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            List<Product> products = new ArrayList<>();
            while (result.next()) {
                products.add(mapProduct(result));
            }
            return products;
        } catch (SQLException exception) {
            throw persistenceFailure("No se pudieron consultar los productos.", exception);
        }
    }

    @Override
    public Optional<Product> findProduct(long productId) {
        String sql = """
                SELECT Id, Clave, Nombre, Descripcion, UnidadMedida, Categoria, Estado, PiezasPorEmpaque
                FROM productos WHERE Id = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, productId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapProduct(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw persistenceFailure("No se pudo consultar el producto.", exception);
        }
    }

     @Override
    public List<InventoryMovement> findMovements(long productId) {
        try (Connection connection = dataSource.getConnection()) {
            return selectMovements(connection, productId);
        } catch (SQLException exception) {
            throw persistenceFailure("No se pudo consultar el historial.", exception);
        }
    }

    @Override
    public Optional<List<InventoryMovement>> findMovementsIfProductExists(long productId) {
        try (Connection connection = dataSource.getConnection()) {
            if (selectProduct(connection, productId, false).isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(selectMovements(connection, productId));
        } catch (SQLException exception) {
            throw persistenceFailure("No se pudo consultar el historial.", exception);
        }
    }


    @Override
    public <T> T withLockedProduct(long productId, Function<ProductTransaction, T> operation) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Product product = selectProduct(connection, productId, true)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "No existe un producto con el ID " + productId + "."));
                ProductTransaction transaction = new JdbcProductTransaction(connection, product);
                T result = operation.apply(transaction);
                connection.commit();
                return result;
            } catch (RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw persistenceFailure("Falló la operación de inventario.", exception);
            }
        } catch (SQLException exception) {
            throw persistenceFailure("No se pudo iniciar la operación de inventario.", exception);
        }
    }

    private static List<InventoryMovement> selectMovements(Connection connection, long productId)
            throws SQLException {
        String sql = """
                SELECT Id, ProductoId, TipoMovimiento, Fecha, Cantidad, CostoUnitario
                FROM movimientos
                WHERE ProductoId = ?
                ORDER BY Fecha, Id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, productId);
            try (ResultSet result = statement.executeQuery()) {
                List<InventoryMovement> movements = new ArrayList<>();
                while (result.next()) {
                    Timestamp timestamp = result.getTimestamp("Fecha");
                    movements.add(new InventoryMovement(
                            result.getLong("Id"),
                            result.getLong("ProductoId"),
                            parseMovementType(result.getString("TipoMovimiento")),
                            timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC),
                            result.getBigDecimal("Cantidad"),
                            result.getBigDecimal("CostoUnitario")));
                }
                return movements;
            }
        }
    }

    private static Optional<Product> selectProduct(
            Connection connection,
            long productId,
            boolean lock) throws SQLException {
        String sql = """
                SELECT Id, Clave, Nombre, Descripcion, UnidadMedida, Categoria, Estado, PiezasPorEmpaque
                FROM productos WHERE Id = ?
                """
                + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, productId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapProduct(result)) : Optional.empty();
            }
        }
    }

    private static Product mapProduct(ResultSet result) throws SQLException {
        return new Product(
                result.getLong("Id"),
                result.getString("Clave"),
                result.getString("Nombre"),
                result.getString("Descripcion"),
                result.getString("UnidadMedida"),
                result.getString("Categoria"),
                result.getBoolean("Estado"),
                result.getInt("PiezasPorEmpaque"));
    }

    private static void setDraft(PreparedStatement statement, ProductDraft draft) throws SQLException {
        statement.setString(1, draft.sku());
        statement.setString(2, draft.name());
        statement.setString(3, draft.description());
        statement.setString(4, draft.unit());
        statement.setString(5, draft.category());
        statement.setBoolean(6, draft.active());
        statement.setInt(7, draft.piecesPerPackage());
    }

    private static MovementType parseMovementType(String value) throws SQLException {
        return switch (value) {
            case "ENTRADA" -> MovementType.ENTRY;
            case "SALIDA" -> MovementType.EXIT;
            default -> throw new SQLException("Tipo de movimiento desconocido: " + value);
        };
    }

    private static void rollback(Connection connection, RuntimeException original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static void rollback(Connection connection, SQLException original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static InventoryPersistenceException persistenceFailure(String message, SQLException cause) {
        return new InventoryPersistenceException(message + " " + cause.getMessage(), cause);
    }

    private static final class JdbcProductTransaction implements ProductTransaction {
        private final Connection connection;
        private final Product product;

        private JdbcProductTransaction(Connection connection, Product product) {
            this.connection = connection;
            this.product = product;
        }

        @Override
        public Product product() {
            return product;
        }

        @Override
        public List<InventoryMovement> movements() {
            try {
                return selectMovements(connection, product.id());
            } catch (SQLException exception) {
                throw persistenceFailure("No se pudo consultar el historial del producto.", exception);
            }
        }

        @Override
        public InventoryMovement insertMovement(
                MovementType type,
                Instant occurredAt,
                BigDecimal quantity,
                BigDecimal unitCost) {
            String sql = """
                    INSERT INTO movimientos
                        (ProductoId, TipoMovimiento, Fecha, Cantidad, CostoUnitario)
                    VALUES (?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, product.id());
                statement.setString(2, type == MovementType.ENTRY ? "ENTRADA" : "SALIDA");
                statement.setTimestamp(3, Timestamp.valueOf(
                        LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC)));
                statement.setBigDecimal(4, quantity);
                statement.setBigDecimal(5, type == MovementType.ENTRY ? unitCost : BigDecimal.ZERO);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("La base de datos no devolvió el ID del movimiento.");
                    }
                    return new InventoryMovement(
                            keys.getLong(1), product.id(), type, occurredAt, quantity,
                            type == MovementType.ENTRY ? unitCost : BigDecimal.ZERO);
                }
            } catch (SQLException exception) {
                throw persistenceFailure("No se pudo guardar el movimiento.", exception);
            }
        }
    }
}