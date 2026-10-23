package com.example.inventory;

import java.awt.GraphicsEnvironment;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Scanner;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

import javax.sql.DataSource;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;

import com.example.inventory.console.ConsoleApp;
import com.example.inventory.gui.InventoryFrame;
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
        String password = System.getenv("SUPABASE_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            JOptionPane.showMessageDialog(
                    null,
                    "Configura la variable de entorno SUPABASE_DB_PASSWORD con la contraseña "
                            + "de PostgreSQL de Supabase y reinicia VS Code. Después, cada persona "
                            + "iniciará sesión con su cuenta de la aplicación.",
                    "Falta configurar la conexión a Supabase",
                    JOptionPane.ERROR_MESSAGE);
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
                    AppUser appUser = UserLoginDialog.authenticate(null, context.userService());
                    if (appUser != null) {
                        try {
                            if (context.userService().darkModeEnabled(appUser.id())) {
                                applyTheme(true);
                            }
                        } catch (RuntimeException ignored) {
                            // Si falla la preferencia, se queda el tema claro.
                        }
                        new InventoryFrame(context.inventoryService(), context.userService(), appUser)
                                .setVisible(true);
                    }
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

    private static void showStartError(String message) {
        JOptionPane.showMessageDialog(null, message, "No se pudo iniciar", JOptionPane.ERROR_MESSAGE);
    }

    private static DataSource createDataSource(String url, String user, String password) {
        try {
            return new DriverManagerDataSource(url, user, password);
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
        if (System.console() == null) {
            throw new IllegalStateException(
                    "No hay una consola interactiva disponible para solicitar la contraseña. "
                            + "Inicia la aplicación desde una terminal PowerShell o configura "
                            + "SUPABASE_DB_PASSWORD en las variables de entorno.");
        }
        char[] enteredPassword = System.console().readPassword("Contraseña de PostgreSQL: ");
        if (enteredPassword == null || enteredPassword.length == 0) {
            throw new IllegalStateException("La contraseña de PostgreSQL es obligatoria.");
        }
        String passwordValue = new String(enteredPassword);
        Arrays.fill(enteredPassword, '\0');
        return passwordValue;
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

    private static final class DriverManagerDataSource implements DataSource {
        private final String url;
        private final String user;
        private final String password;

        private DriverManagerDataSource(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }

        @Override
        public Connection getConnection(String username, String suppliedPassword) throws SQLException {
            return DriverManager.getConnection(url, username, suppliedPassword);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return DriverManager.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            DriverManager.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            DriverManager.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return DriverManager.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return Logger.getLogger("java.sql");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException("No wrapper available for " + iface.getName());
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
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