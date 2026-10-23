package com.example.inventory.service;

import com.example.inventory.model.AppUser;
import com.example.inventory.model.UserRole;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserServiceTest {
    @Test
    void createsFirstAdminAuthenticatesHashedPasswordAndManagesWorkerAccounts() throws Exception {
        UserService service = service("account-flow");
        assertFalse(service.hasUsers());

        AppUser admin = service.createInitialAdmin("Admin", "admin-password-123".toCharArray());
        assertTrue(service.hasUsers());
        assertEquals(UserRole.ADMIN, admin.role());
        assertEquals("admin", service.authenticate("ADMIN", "admin-password-123".toCharArray()).username());
        assertNull(service.authenticate("admin", "incorrect-password".toCharArray()));

        AppUser worker = service.createUser(
                "worker", "worker-password-123".toCharArray(), UserRole.TRABAJADOR);
        assertEquals(UserRole.TRABAJADOR, worker.role());
        assertEquals(2, service.users().size());

        service.setUserActive(worker.id(), false, admin.id());
        assertNull(service.authenticate("worker", "worker-password-123".toCharArray()));
    }

    @Test
    void acceptsAnyNonemptyPasswordAndProtectsTheLastActiveAdmin() throws Exception {
        UserService service = service("account-guards");
        AppUser admin = service.createInitialAdmin("admin", "admin-password-123".toCharArray());

        AppUser worker = service.createUser("short", "a".toCharArray(), UserRole.TRABAJADOR);
        assertEquals("short", service.authenticate("short", "a".toCharArray()).username());
        assertThrows(IllegalArgumentException.class, () -> service.setUserActive(
                admin.id(), false, 999));
        assertEquals(UserRole.ADMIN, service.createUser(
                "short-admin", "!x".toCharArray(), UserRole.ADMIN).role());
        assertThrows(IllegalArgumentException.class, () -> service.createUser(
                "empty-worker", new char[0], UserRole.TRABAJADOR));
        assertThrows(IllegalStateException.class, () -> service.createInitialAdmin(
                "second-admin", "another-password-123".toCharArray()));
        assertEquals(UserRole.TRABAJADOR, worker.role());
    }

    @Test
    void acceptsShortPasswordForInitialAdminAndAuthenticatesIt() throws Exception {
        UserService service = service("short-admin-password");

        AppUser admin = service.createInitialAdmin("admin", "!x".toCharArray());

        assertEquals(UserRole.ADMIN, admin.role());
        assertEquals("admin", service.authenticate("admin", "!x".toCharArray()).username());
        assertThrows(IllegalArgumentException.class, () -> service.createInitialAdmin(
                "empty-admin", new char[0]));
    }

    @Test
    void updatesUsernamePasswordAndPersistsAppearancePreference() throws Exception {
        UserService service = service("account-settings");
        AppUser user = service.createInitialAdmin("admin", "old".toCharArray());

        assertFalse(service.darkModeEnabled(user.id()));
        service.setDarkMode(user.id(), true);
        assertTrue(service.darkModeEnabled(user.id()));

        assertThrows(IllegalArgumentException.class, () ->
                service.changeUsername(user.id(), "InventoryAdmin", "wrong".toCharArray()));
        AppUser renamed = service.changeUsername(user.id(), "InventoryAdmin", "old".toCharArray());
        assertEquals("inventoryadmin", renamed.username());
        assertNull(service.authenticate("admin", "old".toCharArray()));
        assertEquals("inventoryadmin", service.authenticate("inventoryadmin", "old".toCharArray()).username());

        assertThrows(IllegalArgumentException.class, () ->
                service.changePassword(user.id(), "wrong".toCharArray(), "new".toCharArray()));
        service.changePassword(user.id(), "old".toCharArray(), "new".toCharArray());
        assertNull(service.authenticate("inventoryadmin", "old".toCharArray()));
        assertEquals("inventoryadmin", service.authenticate(
                "inventoryadmin", "new".toCharArray()).username());
    }

    private static UserService service(String databaseName) throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:" + databaseName + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE Usuarios (
                        Id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        Nombre_Usuario VARCHAR(50) NOT NULL UNIQUE,
                        Sal VARCHAR(64) NOT NULL,
                        Hash_Contrasena VARCHAR(128) NOT NULL,
                        Rol VARCHAR(20) NOT NULL CHECK (Rol IN ('ADMIN', 'TRABAJADOR')),
                        Activo BOOLEAN NOT NULL DEFAULT TRUE,
                        Modo_Oscuro BOOLEAN NOT NULL DEFAULT FALSE
                    )
                    """);
        }
        return new UserService(dataSource);
    }
}
