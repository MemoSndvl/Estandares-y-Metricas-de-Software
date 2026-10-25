package com.example.inventory;

import java.awt.GraphicsEnvironment;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Scanner;
import java.util.concurrent.ExecutionException;

import javax.sql.DataSource;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.example.inventory.console.ConsoleApp;
import com.example.inventory.gui.InventoryFrame;
import com.example.inventory.gui.Theme;
import com.example.inventory.gui.UserLoginDialog;
import com.example.inventory.model.AppUser;
import com.example.inventory.persistence.SupabasePostgresInventoryRepository;
import com.example.inventory.service.InventoryCostingService;
import com.example.inventory.service.InventoryService;
import com.example.inventory.service.UserService;

public final class Main {
    private static final String DEFAULT_URL =
            "jdbc:postgresql://aws-0-us-east-1.pooler.supabase.com:5432/postgres?sslmode=require";
    private static final String DEFAULT_USER = "postgres.qhdsnxctkcyzgqzhffak";
    private static final String DEFAULT_PASSWORD = "Memoyjuan120314";

    private Main() {
    }

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--console")) {
            startConsole();
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException(
                    "No hay un entorno gráfico disponible. Ejecuta la aplicación en un escritorio "
                            + "o inicia con --console para usar la consola.");
        }
        applyTheme(false);
        SwingUtilities.invokeLater(Main::startGui);
    }

    private static void applyTheme(boolean dark) {
        if (dark) {
            UIManager.put("Panel.background", new java.awt.Color(45, 45, 45));
            UIManager.put("Label.foreground", new java.awt.Color(230, 230, 230));
            UIManager.put("Button.background", new java.awt.Color(65, 65, 65));
            UIManager.put("Button.foreground", new java.awt.Color(230, 230, 230));
            UIManager.put("TextField.background", new java.awt.Color(55, 55, 55));
            UIManager.put("TextField.foreground", new java.awt.Color(230, 230, 230));
            UIManager.put("Table.background", new java.awt.Color(55, 55, 55));
            UIManager.put("Table.foreground", new java.awt.Color(230, 230, 230));
        }
        UIManager.put("Button.arc", 12);
        UIManager.put("Component.arc", 10);
        UIManager.put("TextComponent.arc", 10);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.rowHeight", 28);
    }

    private static void startConsole() {
        DataSource dataSource = createDataSource(
                environmentOrDefault("SUPABASE_DB_URL", DEFAULT_URL),
                environmentOrDefault("SUPABASE_DB_USER", DEFAULT_USER),
                databasePassword());
        verifySupabaseConnection(dataSource);

        InventoryService service = new InventoryService(
                new SupabasePostgresInventoryRepository(dataSource),
                new InventoryCostingService());
        new ConsoleApp(service, new UserService(dataSource), new Scanner(System.in)).run();
    }

    private static void startGui() {
        String password;
        try {
            password = databasePassword();
        } catch (IllegalStateException exception) {
            showStartError(exception.getMessage());
            return;
        }
        String url = environmentOrDefault("SUPABASE_DB_URL", DEFAULT_URL);
        String user = environmentOrDefault("SUPABASE_DB_USER", DEFAULT_USER);

        JOptionPane statusDialog = new JOptionPane(
                "Conectando con PostgreSQL...",
                JOptionPane.INFORMATION_MESSAGE,
                JOptionPane.DEFAULT_OPTION,
                null,
                new Object[] {},
                null);
        JDialog dialog = statusDialog.createDialog("Sistema de inventario");

        SwingWorker<AppContext, Void> worker = new SwingWorker<>() {
            @Override
            protected AppContext doInBackground() {
                DataSource pool = createDataSource(url, user, password);
                verifySupabaseConnection(pool);
                return new AppContext(
                        new InventoryService(
                                new SupabasePostgresInventoryRepository(pool),
                                new InventoryCostingService()),
                        new UserService(pool));
            }

            @Override
            protected void done() {
                dialog.dispose();
                try {
                    AppContext context = get();
                    showLogin(context);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showStartError("Se interrumpió el inicio de la aplicación.");
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    showStartError(cause == null || cause.getMessage() == null
                            ? "Ocurrió un error sin detalles al iniciar."
                            : cause.getMessage());
                } catch (RuntimeException exception) {
                    showStartError(exception.getMessage() == null
                            ? "Ocurrió un error sin detalles al iniciar."
                            : exception.getMessage());
                }
            }
        };
        worker.execute();
        dialog.setVisible(true);
    }

    private static void showLogin(AppContext context) {
        Theme.apply(false, true);
        AppUser appUser = UserLoginDialog.authenticate(null, context.userService());
        if (appUser == null) {
            return;
        }
        boolean darkMode = false;
        try {
            darkMode = context.userService().darkModeEnabled(appUser.id());
        } catch (RuntimeException ignored) {
            // Si falla la preferencia, se queda el tema claro.
        }
        Theme.apply(darkMode, true);
        new InventoryFrame(
                context.inventoryService(),
                context.userService(),
                appUser,
                () -> showLogin(context)).setVisible(true);
    }

    private static void showStartError(String message) {
        JOptionPane.showMessageDialog(null, message, "No se pudo iniciar", JOptionPane.ERROR_MESSAGE);
    }

    private static DataSource createDataSource(String url, String user, String password) {
        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(url);
            config.setUsername(user);
            config.setPassword(password);
            config.setPoolName("inventory-db-pool");
            config.setMaximumPoolSize(10);
            config.setMinimumIdle(1);
            config.setConnectionTimeout(10_000);
            config.setValidationTimeout(5_000);
            return new HikariDataSource(config);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "No se pudo conectar a PostgreSQL de Supabase. Verifica la contraseña de la "
                            + "base de datos, el acceso de red y que SUPABASE_DB_URL use sslmode=require. "
                            + "Detalle: " + exception.getMessage(),
                    exception);
        }
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String databasePassword() {
        String password = System.getenv("SUPABASE_DB_PASSWORD");
        if (password != null && !password.isBlank()) {
            return password;
        }
        return DEFAULT_PASSWORD;
    }

    private static void verifySupabaseConnection(DataSource dataSource) {
        String sql = """
                SELECT to_regclass('public.productos'),
                       to_regclass('public.movimientos'),
                       to_regclass('public.usuarios')
                """;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            if (result.getString(1) == null || result.getString(2) == null || result.getString(3) == null) {
                throw new IllegalStateException(
                        "La conexión a Supabase funciona, pero faltan tablas requeridas. Ejecuta "
                                + "database/schema.sql para crear o actualizar public.productos, "
                                + "public.movimientos y public.usuarios.");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "No se pudo conectar a PostgreSQL de Supabase. Verifica la contraseña de la "
                            + "base de datos, el acceso de red y que SUPABASE_DB_URL use sslmode=require. "
                            + "Detalle: " + sqlExceptionDetails(exception),
                    exception);
        }
    }

    private record AppContext(InventoryService inventoryService, UserService userService) {
    }

    private static String sqlExceptionDetails(SQLException exception) {
        StringBuilder details = new StringBuilder();
        SQLException current = exception;
        while (current != null) {
            if (!details.isEmpty()) {
                details.append(" | ");
            }
            details.append(current.getMessage());
            if (current.getSQLState() != null) {
                details.append(" (SQLState ").append(current.getSQLState()).append(')');
            }
            current = current.getNextException();
        }
        Throwable cause = exception.getCause();
        while (cause != null) {
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                details.append(" | Causa: ").append(cause.getMessage());
            }
            cause = cause.getCause();
        }
        return details.toString();
    }
}