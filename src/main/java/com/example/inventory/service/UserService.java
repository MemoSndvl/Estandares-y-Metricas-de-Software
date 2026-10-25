package com.example.inventory.service;

import com.example.inventory.model.AppUser;
import com.example.inventory.model.UserRole;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.sql.DataSource;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class UserService {
    private static final int SALT_LENGTH = 16;
    private static final int HASH_LENGTH = 256;
    private static final int ITERATIONS = 310_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DataSource dataSource;

    public UserService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public boolean hasUsers() {
        String sql = "SELECT EXISTS (SELECT 1 FROM usuarios)";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getBoolean(1);
        } catch (SQLException exception) {
            throw persistenceFailure("No se pudo consultar la tabla de usuarios.", exception);
        }
    }

    public AppUser authenticate(String username, char[] password) {
        String normalizedUsername = normalizeUsername(username);
        if (password == null || password.length == 0) {
            return null;
        }
        String sql = """
                SELECT id, nombre_usuario, rol, activo, sal, hash_contrasena
                FROM usuarios
                WHERE nombre_usuario = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedUsername);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean("activo")) {
                    return null;
                }
                byte[] salt = Base64.getDecoder().decode(result.getString("sal"));
                byte[] expected = Base64.getDecoder().decode(result.getString("hash_contrasena"));
                if (!MessageDigest.isEqual(expected, passwordHash(password, salt))) {
                    return null;
                }
                return mapUser(result);
            }
        } catch (SQLException exception) {
            throw persistenceFailure("No se pudo iniciar sesión.", exception);
        }
    }

    public AppUser createInitialAdmin(String username, char[] password) {
        String normalizedUsername = normalizeUsername(username);
        validatePassword(password);
        PasswordHash hash = createPasswordHash(password);
        String sql = """
                INSERT INTO usuarios
                    (nombre_usuario, sal, hash_contrasena, rol, activo)
                SELECT ?, ?, ?, 'ADMIN', TRUE
                WHERE NOT EXISTS (SELECT 1 FROM usuarios)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, normalizedUsername);
            statement.setString(2, hash.salt());
            statement.setString(3, hash.hash());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException(
                        "Ya se creó una cuenta. Inicia sesión con la cuenta administradora.");
            }
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("La base de datos no devolvió el ID del administrador.");
                }
                return new AppUser(keys.getLong(1), normalizedUsername, UserRole.ADMIN, true);
            }
        } catch (SQLException exception) {
            throw persistenceFailure("No se pudo crear la cuenta administradora.", exception);
        }
    }

    public List<AppUser> users() {
        String sql = """
                SELECT id, nombre_usuario, rol, activo
                FROM usuarios
                ORDER BY nombre_usuario
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            List<AppUser> users = new ArrayList<>();
            while (result.next()) {
                users.add(mapUser(result));
            }
            return List.copyOf(users);
        } catch (SQLException exception) {
            throw persistenceFailure("No se pudieron consultar las cuentas.", exception);
        }
    }

    public AppUser createUser(String username, char[] password, UserRole role) {
        String normalizedUsername = normalizeUsername(username);
        if (role == null) {
            throw new IllegalArgumentException("Selecciona un rol para la cuenta.");
        }
        validatePassword(password);
        PasswordHash hash = createPasswordHash(password);
        String sql = """
                INSERT INTO usuarios
                    (nombre_usuario, sal, hash_contrasena, rol, activo)
                VALUES (?, ?, ?, ?, TRUE)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, normalizedUsername);
            statement.setString(2, hash.salt());
            statement.setString(3, hash.hash());
            statement.setString(4, role.name());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("La base de datos no devolvió el ID de la cuenta.");
                }
                return new AppUser(keys.getLong(1), normalizedUsername, role, true);
            }
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState())) {
                throw new IllegalArgumentException("Ese nombre de usuario ya está registrado.", exception);
            }
            throw persistenceFailure("No se pudo crear la cuenta.", exception);
        }
    }

    public void setUserActive(long userId, boolean active, long requestingUserId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("La cuenta seleccionada no es válida.");
        }
        if (userId == requestingUserId && !active) {
            throw new IllegalArgumentException("No puedes desactivar tu propia cuenta.");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!active && isLastActiveAdmin(connection, userId)) {
                    throw new IllegalArgumentException(
                            "No se puede desactivar al último administrador activo.");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE usuarios SET activo = ? WHERE id = ?")) {
                    statement.setBoolean(1, active);
                    statement.setLong(2, userId);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalArgumentException("La cuenta seleccionada ya no existe.");
                    }
                }
                connection.commit();
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw persistenceFailure("No se pudo actualizar la cuenta.", exception);
        }
    }

    public AppUser changeUsername(long userId, String username, char[] currentPassword) {
        String normalizedUsername = normalizeUsername(username);
        validatePassword(currentPassword);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                verifyCurrentPassword(connection, userId, currentPassword);
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE usuarios SET nombre_usuario = ? WHERE id = ?")) {
                    statement.setString(1, normalizedUsername);
                    statement.setLong(2, userId);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalArgumentException("La cuenta seleccionada ya no existe.");
                    }
                }
                connection.commit();
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            }
            return user(userId);
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState())) {
                throw new IllegalArgumentException("Ese nombre de usuario ya está registrado.", exception);
            }
            throw persistenceFailure("No se pudo cambiar el nombre de usuario.", exception);
        }
    }

    public void changePassword(long userId, char[] currentPassword, char[] newPassword) {
        validatePassword(currentPassword);
        validatePassword(newPassword);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                verifyCurrentPassword(connection, userId, currentPassword);
                PasswordHash newHash = createPasswordHash(newPassword);
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE usuarios SET sal = ?, hash_contrasena = ? WHERE id = ?")) {
                    statement.setString(1, newHash.salt());
                    statement.setString(2, newHash.hash());
                    statement.setLong(3, userId);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalArgumentException("La cuenta seleccionada ya no existe.");
                    }
                }
                connection.commit();
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw persistenceFailure("No se pudo cambiar la contraseña.", exception);
        }
    }

    private static void verifyCurrentPassword(
            Connection connection, long userId, char[] currentPassword) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sal, hash_contrasena FROM usuarios WHERE id = ? AND activo = TRUE FOR UPDATE")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("La cuenta no existe o está desactivada.");
                }
                byte[] salt = Base64.getDecoder().decode(result.getString("sal"));
                byte[] expected = Base64.getDecoder().decode(result.getString("hash_contrasena"));
                if (!MessageDigest.isEqual(expected, passwordHash(currentPassword, salt))) {
                    throw new IllegalArgumentException("La contraseña actual no es correcta.");
                }
            }
        }
    }

    public boolean darkModeEnabled(long userId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT modo_oscuro FROM usuarios WHERE id = ?")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("La cuenta seleccionada ya no existe.");
                }
                return result.getBoolean("modo_oscuro");
            }
        } catch (SQLException exception) {
            throw persistenceFailure("No se pudo consultar la preferencia de apariencia.", exception);
        }
    }

    public void setDarkMode(long userId, boolean enabled) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE usuarios SET modo_oscuro = ? WHERE id = ?")) {
            statement.setBoolean(1, enabled);
            statement.setLong(2, userId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("La cuenta seleccionada ya no existe.");
            }
        } catch (SQLException exception) {
            throw persistenceFailure("No se pudo guardar la preferencia de apariencia.", exception);
        }
    }

    private AppUser user(long userId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, nombre_usuario, rol, activo FROM usuarios WHERE id = ?")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("La cuenta seleccionada ya no existe.");
                }
                return mapUser(result);
            }
        }
    }

    private static boolean isLastActiveAdmin(Connection connection, long userId) throws SQLException {
        String sql = """
                SELECT rol = 'ADMIN' AND
                    (SELECT COUNT(*) FROM usuarios WHERE rol = 'ADMIN' AND activo = TRUE) = 1
                FROM usuarios
                WHERE id = ? AND activo = TRUE
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static AppUser mapUser(ResultSet result) throws SQLException {
        return new AppUser(
                result.getLong("id"),
                result.getString("nombre_usuario"),
                UserRole.valueOf(result.getString("rol")),
                result.getBoolean("activo"));
    }

    private static String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("El nombre de usuario es obligatorio.");
        }
        String normalized = username.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.length() > 50) {
            throw new IllegalArgumentException("El nombre de usuario no puede superar 50 caracteres.");
        }
        return normalized;
    }

    private static void validatePassword(char[] password) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("La contraseña es obligatoria.");
        }
    }

    private static PasswordHash createPasswordHash(char[] password) {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        return new PasswordHash(
                Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(passwordHash(password, salt)));
    }

    private static byte[] passwordHash(char[] password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, HASH_LENGTH);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("No se pudo proteger la contraseña de la cuenta.", exception);
        } finally {
            spec.clearPassword();
        }
    }

    private static IllegalStateException persistenceFailure(String message, SQLException cause) {
        return new IllegalStateException(message + " " + cause.getMessage(), cause);
    }

    private record PasswordHash(String salt, String hash) {
    }
}
