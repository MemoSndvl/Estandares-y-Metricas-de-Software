package com.example.inventory.console;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

import com.example.inventory.exception.InventoryPersistenceException;
import com.example.inventory.exception.ProductNotFoundException;
import com.example.inventory.model.AppUser;
import com.example.inventory.model.CostingMethod;
import com.example.inventory.model.ExitResult;
import com.example.inventory.model.InventoryMovement;
import com.example.inventory.model.InventoryValuation;
import com.example.inventory.model.MovementType;
import com.example.inventory.model.Product;
import com.example.inventory.model.ProductDraft;
import com.example.inventory.model.UserRole;
import com.example.inventory.service.InventoryService;
import com.example.inventory.service.UserService;

public final class ConsoleApp {
    private final InventoryService service;
    private final UserService userService;
    private final Scanner input;
    private AppUser currentUser;

    public ConsoleApp(InventoryService service, UserService userService, Scanner input) {
        this.service = service;
        this.userService = userService;
        this.input = input;
    }

    public void run() {
        System.out.println("Sistema de inventario conectado a Supabase");
        currentUser = authenticate();
        if (currentUser == null) {
            System.out.println("Inicio de sesión cancelado.");
            return;
        }
        System.out.println("Sesión: " + currentUser.username() + " - " + currentUser.role());

        boolean running = true;
        while (running) {
            printMenu();
            String option = prompt("Opción: ");
            try {
                switch (option) {
                    case "1" -> listProducts();
                    case "2" -> requireAdminAndRun(this::createProduct);
                    case "3" -> requireAdminAndRun(this::updateProduct);
                    case "4" -> registerEntry();
                    case "5" -> registerExit();
                    case "6" -> showValuation();
                    case "7" -> showHistory();
                    case "8" -> requireAdminAndRun(this::manageUsers);
                    case "9" -> accountSettings();
                    case "0" -> running = false;
                    default -> System.out.println("Opción inválida.");
                }
            } catch (IllegalArgumentException | ProductNotFoundException exception) {
                System.out.println("Operación rechazada: " + exception.getMessage());
            } catch (InventoryPersistenceException exception) {
                System.out.println("Error de persistencia: " + exception.getMessage());
            } catch (IllegalStateException exception) {
                System.out.println("Operación no disponible: " + exception.getMessage());
            }
        }
        System.out.println("Aplicación finalizada.");
    }

    private AppUser authenticate() {
        while (!userService.hasUsers()) {
            System.out.println("No hay usuarios. Configura la primera cuenta administradora.");
            String username = prompt("Usuario administrador (vacío para salir): ");
            if (username.isBlank()) {
                return null;
            }
            char[] password = readPassword("Contraseña: ");
            char[] confirmation = readPassword("Confirmar contraseña: ");
            try {
                if (!Arrays.equals(password, confirmation)) {
                    System.out.println("Las contraseñas no coinciden.");
                    continue;
                }
                return userService.createInitialAdmin(username, password);
            } catch (IllegalArgumentException | IllegalStateException exception) {
                System.out.println("No se pudo crear el administrador: " + exception.getMessage());
            } finally {
                Arrays.fill(password, '\0');
                Arrays.fill(confirmation, '\0');
            }
        }

        while (true) {
            String username = prompt("Usuario (vacío para salir): ");
            if (username.isBlank()) {
                return null;
            }
            char[] password = readPassword("Contraseña: ");
            try {
                AppUser user = userService.authenticate(username, password);
                if (user != null) {
                    return user;
                }
                System.out.println("Usuario o contraseña incorrectos, o la cuenta está desactivada.");
            } catch (IllegalArgumentException | IllegalStateException exception) {
                System.out.println("No se pudo iniciar sesión: " + exception.getMessage());
            } finally {
                Arrays.fill(password, '\0');
            }
        }
    }

    private char[] readPassword(String message) {
        if (System.console() != null) {
            char[] password = System.console().readPassword("%s", message);
            return password == null ? new char[0] : password;
        }
        return prompt(message).toCharArray();
    }

    private void requireAdminAndRun(Runnable action) {
        if (currentUser.role() != UserRole.ADMIN) {
            System.out.println("Esta operación solo está disponible para administradores.");
            return;
        }
        action.run();
    }

    private void listProducts() {
        List<Product> products = service.products();
        if (products.isEmpty()) {
            System.out.println("No hay productos registrados.");
            return;
        }
        products.forEach(product -> System.out.printf(
                "%d | %s | %s | %s | %s | %s%n",
                product.id(), product.sku(), product.name(), product.category(), product.stockUnit(),
                product.active() ? "Activo" : "Inactivo"));
    }

    private void createProduct() {
        Product product = service.createProduct(readProductDraft(null));
        System.out.println("Producto guardado con ID " + product.id() + ".");
    }

    private void updateProduct() {
        long id = readLong("ID del producto: ");
        Product existing = service.product(id);
        Product updated = service.updateProduct(id, readProductDraft(existing));
        System.out.println("Producto actualizado: " + updated.name() + ".");
    }

    private ProductDraft readProductDraft(Product existing) {
        String sku = prompt("Clave" + existingValue(existing == null ? null : existing.sku()) + ": ");
        String name = prompt("Nombre" + existingValue(existing == null ? null : existing.name()) + ": ");
        String description = prompt(
                "Descripción" + existingValue(existing == null ? null : existing.description()) + ": ");
        String unit = prompt("Unidad" + existingValue(existing == null ? null : existing.unit()) + ": ");
        String category = prompt(
                "Categoría" + existingValue(existing == null ? null : existing.category()) + ": ");
        String activeText = prompt("Activo (s/n)"
                + existingValue(existing == null ? null : (existing.active() ? "s" : "n")) + ": ");
        boolean active = activeText.isBlank()
                ? existing == null || existing.active()
                : switch (activeText.trim().toLowerCase()) {
                    case "s", "si", "sí" -> true;
                    case "n", "no" -> false;
                    default -> throw new IllegalArgumentException("Indique s o n para el estado del producto.");
                };
        return new ProductDraft(
                sku.isBlank() && existing != null ? existing.sku() : sku,
                name.isBlank() && existing != null ? existing.name() : name,
                description.isBlank() && existing != null ? existing.description() : description,
                unit.isBlank() && existing != null ? existing.unit() : unit,
                category.isBlank() && existing != null ? existing.category() : category,
                active);
    }

    private void registerEntry() {
        long productId = readLong("ID del producto: ");
        Product product = service.product(productId);
        BigDecimal quantity = readDecimal(quantityPrompt(product));
        BigDecimal cost = readDecimal("Costo por " + product.stockUnit() + ": ");
        Instant date = readMovementDate();
        InventoryValuation valuation = service.registerEntry(productId, quantity, cost, date);
        System.out.printf("Entrada registrada. Existencia: %s %s.%n",
                valuation.quantityOnHand().toPlainString(), product.stockUnit());
    }

    private void registerExit() {
        long productId = readLong("ID del producto: ");
        Product product = service.product(productId);
        BigDecimal quantity = readDecimal(quantityPrompt(product));
        CostingMethod method = readMethod();
        Instant date = readMovementDate();
        ExitResult result = service.registerExit(productId, quantity, date, method);
        System.out.printf("Salida registrada. Costo: %s. Existencia: %s %s.%n",
                result.issueCost().toPlainString(),
                result.valuation().quantityOnHand().toPlainString(), product.stockUnit());
    }

    private static String quantityPrompt(Product product) {
        return product.packaged()
                ? "Cantidad (" + product.unit() + ", " + product.piecesPerPackage() + " pza por empaque): "
                : "Cantidad (" + product.stockUnit() + "): ";
    }

    private void showValuation() {
        long productId = readLong("ID del producto: ");
        CostingMethod method = readMethod();
        InventoryValuation valuation = service.valuation(productId, method);
        System.out.println("Método: " + method);
        System.out.println("Existencia: " + valuation.quantityOnHand().toPlainString());
        System.out.println("Valor del inventario: $" + valuation.inventoryValue().toPlainString());
        System.out.println("Costo unitario actual: " + (valuation.unitCost() == null
                ? "sin existencias"
                : "$" + valuation.unitCost().toPlainString()));
    }

    private void showHistory() {
        long productId = readLong("ID del producto: ");
        CostingMethod method = readMethod();
        List<InventoryMovement> movements = service.history(productId);
        InventoryValuation valuation = service.valuation(productId, method);
        if (movements.isEmpty()) {
            System.out.println("El producto todavía no tiene movimientos.");
            return;
        }
        for (InventoryMovement movement : movements) {
            String cost = movement.type() == MovementType.ENTRY
                    ? movement.unitCost().toPlainString()
                    : valuation.issueCosts().get(movement.id()).toPlainString();
            System.out.printf("%s | %s | cantidad %s | costo %s%n",
                    movement.occurredAt(), movement.type(),
                    movement.quantity().toPlainString() + " " + service.product(productId).stockUnit(), cost);
        }
        System.out.println("Costos de salida calculados con " + method + ".");
    }

    private CostingMethod readMethod() {
        String method = prompt("Método (1=PEPS, 2=Promedio ponderado): ");
        return switch (method) {
            case "1" -> CostingMethod.PEPS;
            case "2" -> CostingMethod.PROMEDIO;
            default -> throw new IllegalArgumentException("Seleccione 1 para PEPS o 2 para Promedio.");
        };
    }

    private Instant readDate() {
        String date = prompt("Fecha (AAAA-MM-DD, vacío=hoy UTC): ");
        try {
            LocalDate parsed = date.isBlank() ? LocalDate.now(ZoneOffset.UTC) : LocalDate.parse(date.trim());
            return parsed.atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("La fecha debe tener el formato AAAA-MM-DD.");
        }
    }

    private Instant readMovementDate() {
        if (currentUser.role() == UserRole.ADMIN) {
            return readDate();
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        System.out.println("Fecha asignada automáticamente: " + today);
        return today.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private void manageUsers() {
        boolean managing = true;
        while (managing) {
            System.out.println("""

                    Administración de usuarios
                    1. Listar usuarios
                    2. Crear usuario
                    3. Activar / desactivar usuario
                    0. Volver""");
            switch (prompt("Opción: ")) {
                case "1" -> listUsers();
                case "2" -> createUser();
                case "3" -> toggleUser();
                case "0" -> managing = false;
                default -> System.out.println("Opción inválida.");
            }
        }
    }

    private void accountSettings() {
        boolean managing = true;
        while (managing) {
            System.out.println("""

                    Configuración de usuario
                    1. Cambiar nombre de usuario
                    2. Cambiar contraseña
                    3. Activar / desactivar modo noche para la interfaz gráfica
                    0. Volver""");
            switch (prompt("Opción: ")) {
                case "1" -> changeOwnUsername();
                case "2" -> changeOwnPassword();
                case "3" -> toggleDarkMode();
                case "0" -> managing = false;
                default -> System.out.println("Opción inválida.");
            }
        }
    }

    private void changeOwnUsername() {
        char[] currentPassword = readPassword("Contraseña actual: ");
        try {
            currentUser = userService.changeUsername(
                    currentUser.id(), prompt("Nuevo nombre de usuario: "), currentPassword);
            System.out.println("Nombre de usuario actualizado.");
        } finally {
            Arrays.fill(currentPassword, '\0');
        }
    }

    private void changeOwnPassword() {
        char[] currentPassword = readPassword("Contraseña actual: ");
        char[] newPassword = readPassword("Contraseña nueva: ");
        char[] confirmation = readPassword("Confirmar contraseña nueva: ");
        try {
            if (!Arrays.equals(newPassword, confirmation)) {
                throw new IllegalArgumentException("Las contraseñas nuevas no coinciden.");
            }
            userService.changePassword(currentUser.id(), currentPassword, newPassword);
            System.out.println("Contraseña actualizada.");
        } finally {
            Arrays.fill(currentPassword, '\0');
            Arrays.fill(newPassword, '\0');
            Arrays.fill(confirmation, '\0');
        }
    }

    private void toggleDarkMode() {
        boolean enabled = !userService.darkModeEnabled(currentUser.id());
        userService.setDarkMode(currentUser.id(), enabled);
        System.out.println("Modo noche " + (enabled ? "activado" : "desactivado")
                + " para la interfaz gráfica.");
    }

    private void listUsers() {
        userService.users().forEach(user -> System.out.printf(
                "%d | %s | %s | %s%n",
                user.id(), user.username(), user.role(), user.active() ? "Activo" : "Inactivo"));
    }

    private void createUser() {
        String username = prompt("Nombre de usuario: ");
        UserRole role = switch (prompt("Rol (1=Administrador, 2=Trabajador): ")) {
            case "1" -> UserRole.ADMIN;
            case "2" -> UserRole.TRABAJADOR;
            default -> throw new IllegalArgumentException("Seleccione 1 para Administrador o 2 para Trabajador.");
        };
        char[] password = readPassword("Contraseña: ");
        char[] confirmation = readPassword("Confirmar contraseña: ");
        try {
            if (!Arrays.equals(password, confirmation)) {
                throw new IllegalArgumentException("Las contraseñas no coinciden.");
            }
            AppUser user = userService.createUser(username, password, role);
            System.out.println("Cuenta creada: " + user.username() + ".");
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(confirmation, '\0');
        }
    }

    private void toggleUser() {
        long userId = readLong("ID de usuario: ");
        AppUser selected = userService.users().stream()
                .filter(user -> user.id() == userId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("La cuenta seleccionada no existe."));
        userService.setUserActive(userId, !selected.active(), currentUser.id());
        System.out.println("Cuenta " + (selected.active() ? "desactivada" : "activada") + ".");
    }

    private long readLong(String label) {
        try {
            return Long.parseLong(prompt(label).trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Ingrese un ID numérico válido.");
        }
    }

    private BigDecimal readDecimal(String label) {
        try {
            return new BigDecimal(prompt(label).trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Ingrese un número decimal válido, por ejemplo 12.50.");
        }
    }

    private String prompt(String label) {
        System.out.print(label);
        return input.nextLine();
    }

    private static String existingValue(String value) {
        return value == null ? "" : " [" + value + "]";
    }

    private void printMenu() {
        System.out.println("""

                1. Consultar productos
                2. Registrar producto (administrador)
                3. Modificar producto (administrador)
                4. Registrar entrada
                5. Registrar salida
                6. Consultar existencias y valoración
                7. Consultar historial
                9. Configuración de usuario""");
        if (currentUser.role() == UserRole.ADMIN) {
            System.out.println("                8. Administrar usuarios");
        }
        System.out.println("                0. Salir");
    }
}
