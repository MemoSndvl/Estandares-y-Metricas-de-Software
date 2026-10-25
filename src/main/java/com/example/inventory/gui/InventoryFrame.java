package com.example.inventory.gui;

import javax.accessibility.AccessibleContext;
import com.example.inventory.exception.InventoryPersistenceException;
import com.example.inventory.exception.ProductNotFoundException;
import com.example.inventory.model.AppUser;
import com.example.inventory.model.CostingMethod;
import com.example.inventory.model.InventoryLedgerRow;
import com.example.inventory.model.InventoryMovement;
import com.example.inventory.model.InventoryValuation;
import com.example.inventory.model.MovementType;
import com.example.inventory.model.Product;
import com.example.inventory.model.ProductDraft;
import com.example.inventory.model.UserRole;
import com.example.inventory.service.InventoryService;
import com.example.inventory.service.UserService;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import javax.swing.text.JTextComponent;

public final class InventoryFrame extends JFrame {
    private static final String[] COMMON_UNITS = {
            "pza", "kg", "g", "mg", "t", "L", "ml", "m", "cm", "mm",
            "caja", "paquete", "docena", "par", "rollo", "bolsa", "botella", "servicio"
    };

    private final InventoryService service;
    private final UserService userService;
    private AppUser currentUser;
    private final List<Runnable> themeBindings = new ArrayList<>();

    private final DefaultTableModel inventoryModel = tableModel(
            "Clave", "Producto", "Unidad", "Existencia", "Valor inventario", "Estado");
    private final DefaultTableModel productsModel = tableModel(
            "ID", "Clave", "Producto", "Categoría", "Unidad", "Descripción", "Estado");
    private final DefaultTableModel movementsModel = tableModel(
            "Fecha", "Tipo", "Cantidad", "Costo unitario / salida", "Importe", "Responsable", "Motivo");
    private final DefaultTableModel ledgerModel = tableModel(
            "Fecha",
            "<html>Compras<br>Cantidad</html>", "<html>Compras<br>Costo unitario</html>",
            "<html>Compras<br>Costo total</html>",
            "<html>Ventas<br>Cantidad</html>", "<html>Ventas<br>Costo unitario</html>",
            "<html>Ventas<br>Costo total</html>",
            "<html>Ajustes +<br>Cantidad</html>", "<html>Ajustes +<br>Costo unitario</html>",
            "<html>Ajustes +<br>Costo total</html>",
            "<html>Ajustes -<br>Cantidad</html>", "<html>Ajustes -<br>Costo unitario</html>",
            "<html>Ajustes -<br>Costo total</html>",
            "<html>Saldos<br>Cantidad</html>", "<html>Saldos<br>Costo unitario</html>",
            "<html>Saldos<br>Costo total</html>");
    private final DefaultTableModel usersModel = tableModel(
            "ID", "Usuario", "Rol", "Estado");
    private final JTable inventoryTable = new JTable(inventoryModel);
    private final JTable productsTable = new JTable(productsModel);
    private final JTable movementsTable = new JTable(movementsModel);
    private final JTable ledgerTable = new JTable(ledgerModel);
    private final JTable usersTable = new JTable(usersModel);
    private final TableRowSorter<DefaultTableModel> productsSorter = new TableRowSorter<>(productsModel);
    private final JTextField productSearchField = new JTextField(22);
    private final JComboBox<String> productCategoryFilter = new JComboBox<>();
    private final CardLayout productsResultsLayout = new CardLayout();
    private final JPanel productsResults = new JPanel(productsResultsLayout);
    private final JLabel productsEmptyState = new JLabel("No se encontraron productos", SwingConstants.CENTER);

    private final JComboBox<Product> movementProduct = new JComboBox<>();
    private final JComboBox<CostingMethod> costingMethod = new JComboBox<>(CostingMethod.values());
    private final JComboBox<CategoryChoice> ledgerCategory = new JComboBox<>();
    private final JComboBox<Product> ledgerProduct = new JComboBox<>();

    private final JLabel status = new JLabel("Listo");
    private final JLabel productCount = new JLabel("—");
    private final JLabel activeCount = new JLabel("—");
    private final JLabel inventoryValue = new JLabel("—");
    private final JLabel outOfStockCount = new JLabel("—");
    private final JLabel pageHeading = new JLabel("Resumen");
    private final BarChartPanel categoryChart = new BarChartPanel();
    private final GaugePanel stockGauge = new GaugePanel();

    private final CardLayout pageLayout = new CardLayout();
    private final JPanel pages = new JPanel(pageLayout);
    private final Map<String, NavButton> navigationButtons = new LinkedHashMap<>();
    private JPanel sidebar;
    private JPanel sidebarContent;
    private JLabel sidebarLogo;
    private JLabel sidebarBrandName;
    private HamburgerButton sidebarToggle;
    private boolean sidebarExpanded = true;
    private Timer sidebarAnimation;
    private UserChip userChip;
    private ThemeToggle themeToggle;

    private boolean darkMode;
    private int textScalePercent = 100;
    private boolean highContrast;
    private boolean reducedMotion;
    private boolean mouseNarrationEnabled;
    private JCheckBox narrationToggle;
    private AWTEventListener mouseNarrationListener;
    private Timer mouseNarrationDelay;
    private Component pendingNarrationTarget;
    private MouseEvent pendingNarrationEvent;
    private Component lastNarratedTarget;
    private String lastNarratedText;
    private VoiceNarrator voiceNarrator;
    private String activePage = "summary";
    private List<Product> products = List.of();
    private boolean updatingLedgerFilters;
    private boolean updatingMovementProducts;
    private final Runnable reloginAction;

    public InventoryFrame(InventoryService service, UserService userService, AppUser currentUser) {
        this(service, userService, currentUser, null);
    }

    public InventoryFrame(InventoryService service, UserService userService, AppUser currentUser,
                          Runnable reloginAction) {
        super("Sistema de inventario");
        this.service = service;
        this.userService = userService;
        this.currentUser = currentUser;
        this.reloginAction = reloginAction;
        this.darkMode = Theme.isDark();
        loadAccessibilityOptions();
        Theme.setAccessibilityOptions(textScalePercent, highContrast, reducedMotion);
        Theme.apply(darkMode, false);
        voiceNarrator = new VoiceNarrator(message -> SwingUtilities.invokeLater(
                () -> handleNarrationError(message)));
        setDefaultCloseOperation(reloginAction == null ? JFrame.EXIT_ON_CLOSE : JFrame.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                stopMouseNarration();
                if (reloginAction != null) {
                    reloginAction.run();
                }
            }
        });
        setMinimumSize(new Dimension(1180, 740));
        setSize(1380, 860);
        setLocationRelativeTo(null);
        setExtendedState(JFrame.MAXIMIZED_BOTH);

        JPanel root = new JPanel(new BorderLayout());
        bind(() -> root.setBackground(Theme.background()));
        setContentPane(root);
        root.add(createSidebar(), BorderLayout.WEST);

        JPanel main = new JPanel(new BorderLayout());
        main.setOpaque(false);
        main.add(createHeader(), BorderLayout.NORTH);
        main.add(createPages(), BorderLayout.CENTER);
        main.add(createStatusBar(), BorderLayout.SOUTH);
        root.add(main, BorderLayout.CENTER);

        showPage("summary", "Resumen");
        if (mouseNarrationEnabled) {
            setMouseNarrationEnabled(true, false);
        }
        reload();
    }

    // ------------------------------------------------------------------ tema

    private void bind(Runnable binding) {
        binding.run();
        themeBindings.add(binding);
    }

    private JLabel textLabel(String text, int style, float size) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.font(style, size));
        bind(() -> label.setForeground(Theme.text()));
        return label;
    }

    private JLabel mutedLabel(String text, float size) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.font(Font.PLAIN, size));
        bind(() -> label.setForeground(Theme.muted()));
        return label;
    }

    private void setDarkMode(boolean dark) {
        if (dark == darkMode) {
            return;
        }
        darkMode = dark;
        Theme.apply(dark, true);
        themeBindings.forEach(Runnable::run);
        themeToggle.repaint();
        repaint();
        runTask("Guardando preferencia...", () -> {
            userService.setDarkMode(currentUser.id(), dark);
            return Boolean.TRUE;
        }, saved -> status.setText(dark ? "Modo oscuro activado" : "Modo claro activado"));
    }

    private Preferences accessibilityPreferences() {
        return Preferences.userNodeForPackage(InventoryFrame.class).node(Long.toString(currentUser.id()));
    }

    private void loadAccessibilityOptions() {
        Preferences preferences = accessibilityPreferences();
        int savedScale = preferences.getInt("textScalePercent", 100);
        textScalePercent = savedScale == 125 || savedScale == 150 ? savedScale : 100;
        highContrast = preferences.getBoolean("highContrast", false);
        reducedMotion = preferences.getBoolean("reducedMotion", false);
        mouseNarrationEnabled = preferences.getBoolean("mouseNarration", false);
    }

    private void updateAccessibilityOptions(int newScale, boolean useHighContrast, boolean useReducedMotion) {
        int previousScale = textScalePercent;
        textScalePercent = newScale;
        highContrast = useHighContrast;
        reducedMotion = useReducedMotion;
        Theme.setAccessibilityOptions(textScalePercent, highContrast, reducedMotion);
        if (previousScale != newScale) {
            float scaleRatio = (float) newScale / previousScale;
            for (Window window : Window.getWindows()) {
                scaleFonts(window, scaleRatio);
            }
        }
        Theme.apply(darkMode, true);
        themeBindings.forEach(Runnable::run);
        repaint();
        try {
            Preferences preferences = accessibilityPreferences();
            preferences.putInt("textScalePercent", textScalePercent);
            preferences.putBoolean("highContrast", highContrast);
            preferences.putBoolean("reducedMotion", reducedMotion);
            preferences.flush();
            status.setText("Opciones de accesibilidad guardadas");
        } catch (BackingStoreException | SecurityException exception) {
            showMessage("Las opciones se aplicaron, pero no se pudieron guardar en este equipo: "
                    + exception.getMessage(), JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void scaleFonts(Component component, float scaleRatio) {
        Font font = component.getFont();
        if (font != null) {
            component.setFont(font.deriveFont(font.getSize2D() * scaleRatio));
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                scaleFonts(child, scaleRatio);
            }
        }
    }

    private void setMouseNarrationEnabled(boolean enabled) {
        setMouseNarrationEnabled(enabled, true);
    }

    private void setMouseNarrationEnabled(boolean enabled, boolean persist) {
        if (enabled && !VoiceNarrator.isSupported()) {
            if (narrationToggle != null) {
                narrationToggle.setSelected(false);
            }
            showMessage("La narración por voz está disponible en Windows.", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        mouseNarrationEnabled = enabled;
        if (enabled) {
            if (voiceNarrator == null) {
                voiceNarrator = new VoiceNarrator(message -> SwingUtilities.invokeLater(
                        () -> handleNarrationError(message)));
            }
            if (mouseNarrationListener == null) {
                mouseNarrationListener = event -> {
                    if (event instanceof MouseEvent mouseEvent
                            && (mouseEvent.getID() == MouseEvent.MOUSE_ENTERED
                                    || mouseEvent.getID() == MouseEvent.MOUSE_MOVED)
                            && mouseEvent.getSource() instanceof Component component) {
                        scheduleMouseNarration(component, mouseEvent);
                    }
                };
                try {
                    Toolkit.getDefaultToolkit().addAWTEventListener(
                            mouseNarrationListener,
                            AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
                } catch (SecurityException exception) {
                    mouseNarrationListener = null;
                    mouseNarrationEnabled = false;
                    if (narrationToggle != null) {
                        narrationToggle.setSelected(false);
                    }
                    showMessage("El sistema no permitió activar la narración: " + exception.getMessage(),
                            JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }
        } else {
            stopMouseNarration();
            voiceNarrator = null;
            lastNarratedTarget = null;
            lastNarratedText = null;
        }
        if (persist) {
            saveMouseNarrationPreference(enabled);
        }
    }

    private void saveMouseNarrationPreference(boolean enabled) {
        try {
            Preferences preferences = accessibilityPreferences();
            preferences.putBoolean("mouseNarration", enabled);
            preferences.flush();
        } catch (BackingStoreException | SecurityException exception) {
            showMessage("La opción se aplicó, pero no se pudo guardar en este equipo: "
                    + exception.getMessage(), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void stopMouseNarration() {
        if (mouseNarrationListener != null) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(mouseNarrationListener);
            mouseNarrationListener = null;
        }
        if (mouseNarrationDelay != null) {
            mouseNarrationDelay.stop();
            mouseNarrationDelay = null;
        }
        pendingNarrationTarget = null;
        pendingNarrationEvent = null;
        if (voiceNarrator != null) {
            voiceNarrator.close();
            voiceNarrator = null;
        }
        mouseNarrationEnabled = false;
    }

    private void handleNarrationError(String message) {
        if (!mouseNarrationEnabled) {
            return;
        }
        if (narrationToggle != null) {
            narrationToggle.setSelected(false);
        }
        setMouseNarrationEnabled(false);
        showMessage(message, JOptionPane.ERROR_MESSAGE);
    }

    private void scheduleMouseNarration(Component target, MouseEvent event) {
        if (mouseNarrationDelay == null) {
            mouseNarrationDelay = new Timer(650, ignored -> {
                Component targetToRead = pendingNarrationTarget;
                MouseEvent eventToRead = pendingNarrationEvent;
                pendingNarrationTarget = null;
                pendingNarrationEvent = null;
                if (targetToRead == null || eventToRead == null) {
                    return;
                }
                String description = describeMouseTarget(targetToRead, eventToRead);
                if (description != null
                        && (targetToRead != lastNarratedTarget || !description.equals(lastNarratedText))) {
                    lastNarratedTarget = targetToRead;
                    lastNarratedText = description;
                    if (voiceNarrator != null) {
                        voiceNarrator.speak("Por aquí: " + description);
                    }
                }
            });
            mouseNarrationDelay.setRepeats(false);
        }
        pendingNarrationTarget = target;
        pendingNarrationEvent = event;
        mouseNarrationDelay.restart();
    }

    private static String describeMouseTarget(Component component, MouseEvent event) {
        if (component instanceof JTable table) {
            int row = table.rowAtPoint(event.getPoint());
            int column = table.columnAtPoint(event.getPoint());
            if (row >= 0 && column >= 0) {
                Object value = table.getValueAt(row, column);
                String columnName = table.getColumnName(column);
                return columnName + (value == null || value.toString().isBlank() ? "" : ": " + value);
            }
        }
        if (component instanceof AbstractButton button) {
            String text = cleanAccessibleText(button.getText());
            if (text != null) {
                return button instanceof JCheckBox
                        ? text + (button.isSelected() ? ", activado" : ", desactivado")
                        : text;
            }
        } else if (component instanceof JLabel label) {
            String text = cleanAccessibleText(label.getText());
            if (text != null) {
                return text;
            }
        } else if (component instanceof JComboBox<?> combo) {
            Object selected = combo.getSelectedItem();
            if (selected != null) {
                return componentName(component) + ": " + selected;
            }
        } else if (component instanceof JTabbedPane tabs) {
            int selected = tabs.getSelectedIndex();
            if (selected >= 0) {
                return "Pestaña " + tabs.getTitleAt(selected);
            }
        } else if (component instanceof JSpinner spinner) {
            return componentName(component) + ": " + spinner.getValue();
        } else if (component instanceof JSlider slider) {
            return componentName(component) + ": " + slider.getValue();
        } else if (component instanceof JTextComponent) {
            return componentName(component);
        }
        String name = componentName(component);
        return name.isBlank() ? null : name;
    }

    private static String componentName(Component component) {
        String name = null;
        if (component.getAccessibleContext() != null) {
            AccessibleContext accessible = component.getAccessibleContext();
            name = cleanAccessibleText(accessible.getAccessibleName());
            if (name == null) {
                name = cleanAccessibleText(accessible.getAccessibleDescription());
            }
        }
        if (name == null && component instanceof JComponent swingComponent) {
            name = cleanAccessibleText(swingComponent.getToolTipText());
        }
        return name == null ? "" : name;
    }

    private static String cleanAccessibleText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------- estructura

    private JPanel createSidebar() {
        sidebar = new JPanel(new BorderLayout());
        sidebar.setBackground(Theme.SIDEBAR_BG);
        sidebar.setPreferredSize(new Dimension(252, 0));

        JPanel brand = new JPanel(new BorderLayout(8, 0));
        brand.setBackground(Theme.SIDEBAR_BG);
        brand.setBorder(BorderFactory.createEmptyBorder(12, 10, 12, 10));
        JPanel brandIdentity = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        brandIdentity.setOpaque(false);
        sidebarLogo = new JLabel(new Theme.LogoIcon(34));
        brandIdentity.add(sidebarLogo);
        sidebarBrandName = new JLabel("Inventario");
        sidebarBrandName.setForeground(Color.WHITE);
        sidebarBrandName.setFont(Theme.font(Font.BOLD, 20f));
        sidebarBrandName.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
        brandIdentity.add(sidebarBrandName);
        brand.add(brandIdentity, BorderLayout.CENTER);

        sidebarToggle = new HamburgerButton();
        sidebarToggle.addActionListener(event -> toggleSidebar());
        brand.add(sidebarToggle, BorderLayout.EAST);
        sidebar.add(brand, BorderLayout.NORTH);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Theme.SIDEBAR_BG);
        content.setBorder(BorderFactory.createEmptyBorder(14, 16, 20, 16));
        sidebarContent = content;

        addNavigationGroup(content, "MENÚ PRINCIPAL");
        addNavigationItem(content, "Resumen", "summary");
        addNavigationItem(content, "Productos", "products");
        addNavigationItem(content, "Movimientos", "movements");
        addNavigationItem(content, "Kardex promedio", "ledger");
        if (currentUser.role() == UserRole.ADMIN) {
            content.add(Box.createVerticalStrut(14));
            addNavigationGroup(content, "ADMINISTRACIÓN");
            addNavigationItem(content, "Usuarios", "users");
        }
        content.add(Box.createVerticalGlue());

        JLabel connection = new JLabel("<html><font color='#A8EC26'>●</font>&nbsp; PostgreSQL · Supabase</html>");
        connection.setForeground(Theme.sidebarText());
        connection.setFont(Theme.font(Font.PLAIN, 12f));
        connection.setAlignmentX(Component.LEFT_ALIGNMENT);
        connection.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
        content.add(connection);
        content.add(Box.createVerticalStrut(12));

        NavButton logout = new NavButton("Cerrar sesión");
        logout.setToolTipText("Cerrar la sesión actual");
        logout.getAccessibleContext().setAccessibleName("Cerrar sesión");
        logout.addActionListener(event -> {
            int answer = JOptionPane.showConfirmDialog(
                    this,
                    "¿Deseas cerrar la sesión actual?",
                    "Cerrar sesión",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (answer == JOptionPane.YES_OPTION) {
                if (reloginAction == null) {
                    dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING));
                } else {
                    dispose();
                }
            }
        });
        content.add(logout);

        sidebar.add(sidebarContent, BorderLayout.CENTER);
        return sidebar;
    }

    private void toggleSidebar() {
        sidebarExpanded = !sidebarExpanded;
        sidebarContent.setVisible(sidebarExpanded);
        sidebarLogo.setVisible(sidebarExpanded);
        sidebarBrandName.setVisible(sidebarExpanded);
        sidebarToggle.setExpanded(sidebarExpanded);
        sidebarToggle.setToolTipText(sidebarExpanded ? "Contraer menú" : "Expandir menú");
        sidebarToggle.getAccessibleContext().setAccessibleName(
                sidebarExpanded ? "Contraer menú" : "Expandir menú");

        if (sidebarAnimation != null && sidebarAnimation.isRunning()) {
            sidebarAnimation.stop();
        }
        int targetWidth = sidebarExpanded ? 252 : 60;
        if (reducedMotion) {
            sidebar.setPreferredSize(new Dimension(targetWidth, 0));
            sidebar.revalidate();
            sidebar.getParent().revalidate();
            sidebar.getParent().repaint();
            return;
        }
        sidebarAnimation = new Timer(15, event -> {
            int currentWidth = sidebar.getPreferredSize().width;
            int difference = targetWidth - currentWidth;
            if (Math.abs(difference) <= 16) {
                sidebar.setPreferredSize(new Dimension(targetWidth, 0));
                sidebarAnimation.stop();
            } else {
                sidebar.setPreferredSize(new Dimension(currentWidth + Integer.signum(difference) * 16, 0));
            }
            sidebar.revalidate();
            sidebar.getParent().revalidate();
            sidebar.getParent().repaint();
        });
        sidebarAnimation.start();
    }

    private void addNavigationGroup(JPanel container, String label) {
        JLabel heading = new JLabel(label);
        heading.setForeground(Theme.sidebarMuted());
        heading.setFont(Theme.font(Font.BOLD, 11f));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        heading.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 0));
        container.add(heading);
    }

    private void addNavigationItem(JPanel container, String label, String page) {
        NavButton item = new NavButton(label);
        item.addActionListener(event -> {
            showPage(page, label);
            if ("ledger".equals(page)) {
                loadLedger();
            }
        });
        navigationButtons.put(page, item);
        container.add(item);
        container.add(Box.createVerticalStrut(4));
    }

    private void showPage(String page, String title) {
        activePage = page;
        pageLayout.show(pages, page);
        pageHeading.setText(title);
        navigationButtons.forEach((key, item) -> item.setActive(key.equals(page)));
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(20, 28, 10, 28));

        pageHeading.setFont(Theme.font(Font.BOLD, 26f));
        bind(() -> pageHeading.setForeground(Theme.text()));
        JLabel breadcrumb = mutedLabel("Inventario  /  " + roleLabel(currentUser.role()), 12f);
        JPanel copy = new JPanel(new GridLayout(2, 1, 0, 2));
        copy.setOpaque(false);
        copy.add(pageHeading);
        copy.add(breadcrumb);

        JPanel left = new JPanel(new BorderLayout(14, 0));
        left.setOpaque(false);
        left.add(copy, BorderLayout.CENTER);

        themeToggle = new ThemeToggle();
        themeToggle.addActionListener(event -> setDarkMode(!darkMode));
        userChip = new UserChip(currentUser.username(), roleLabel(currentUser.role()));
        userChip.addActionListener(event -> showUserSettings());

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
        right.setOpaque(false);
        right.add(themeToggle);
        right.add(userChip);

        header.add(left, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JPanel createPages() {
        JPanel content = new JPanel(new BorderLayout());
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(6, 28, 6, 28));
        pages.setOpaque(false);
        pages.add(createSummaryPage(), "summary");
        pages.add(createProductsPage(), "products");
        pages.add(createMovementsPage(), "movements");
        pages.add(createLedgerPage(), "ledger");
        if (currentUser.role() == UserRole.ADMIN) {
            pages.add(createUsersPage(), "users");
        }
        content.add(pages, BorderLayout.CENTER);
        return content;
    }

    private JPanel createStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(8, 28, 12, 28));
        status.setFont(Theme.font(Font.PLAIN, 12f));
        bind(() -> status.setForeground(Theme.muted()));
        bar.add(status, BorderLayout.WEST);
        JLabel connection = mutedLabel("", 12f);
        connection.setText("<html><font color='#22C55E'>●</font>&nbsp; PostgreSQL · Supabase</html>");
        bar.add(connection, BorderLayout.EAST);
        return bar;
    }

    // ---------------------------------------------------------------- páginas

    private JPanel createSummaryPage() {
        JPanel page = new JPanel(new BorderLayout(0, 16));
        page.setOpaque(false);

        JPanel cards = new JPanel(new GridLayout(1, 4, 16, 0));
        cards.setOpaque(false);
        cards.setPreferredSize(new Dimension(0, 104));
        cards.add(statCard("Productos registrados", productCount, Theme.BLUE));
        cards.add(statCard("Productos activos", activeCount, Theme.GREEN));
        cards.add(statCard("Valor del inventario · PEPS", inventoryValue, Theme.LIME));
        cards.add(statCard("Activos sin existencia", outOfStockCount, Theme.ORANGE));

        JPanel charts = new JPanel(new GridLayout(1, 2, 16, 0));
        charts.setOpaque(false);
        charts.setPreferredSize(new Dimension(0, 236));
        charts.add(chartCard("Valor por categoría",
                "Las 6 categorías con más valor en inventario", categoryChart));
        charts.add(chartCard("Disponibilidad",
                "Productos activos que tienen existencia", stockGauge));

        JPanel top = new JPanel(new BorderLayout(0, 16));
        top.setOpaque(false);
        top.add(cards, BorderLayout.NORTH);
        top.add(charts, BorderLayout.CENTER);

        page.add(top, BorderLayout.NORTH);
        page.add(tableCard(sectionHeader("Existencias actuales", null),
                inventoryTable, 46, 3, 4), BorderLayout.CENTER);
        inventoryTable.getColumnModel().getColumn(5)
                .setCellRenderer(new ChipRenderer(InventoryFrame::statusColors));
        inventoryTable.setAutoCreateRowSorter(true);
        return page;
    }

    private JPanel createProductsPage() {
        JPanel page = new JPanel(new BorderLayout());
        page.setOpaque(false);
        JComponent buttons = null;
        if (currentUser.role() == UserRole.ADMIN) {
            JButton add = button("Nuevo producto", true);
            JButton edit = button("Editar seleccionado", false);
            add.addActionListener(event -> showProductDialog(null));
            edit.addActionListener(event -> editSelectedProduct());
            buttons = buttonRow(add, edit);
        }
        productsTable.setRowSorter(productsSorter);
        productSearchField.setFont(Theme.font(Font.PLAIN, 13f));
        productCategoryFilter.setFont(Theme.font(Font.PLAIN, 13f));
        bind(() -> {
            productSearchField.setBackground(Theme.surface());
            productSearchField.setForeground(Theme.text());
        });

        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        filters.setOpaque(false);
        filters.add(mutedLabel("Buscar", 13f));
        filters.add(productSearchField);
        filters.add(mutedLabel("Categoría", 13f));
        filters.add(productCategoryFilter);
        JButton clearFilters = button("Limpiar filtros", false);
        filters.add(clearFilters);

        JPanel cardHeader = new JPanel(new BorderLayout(0, 12));
        cardHeader.setOpaque(false);
        cardHeader.add(sectionHeader("Catálogo de productos", buttons), BorderLayout.NORTH);
        cardHeader.add(filters, BorderLayout.SOUTH);

        productsEmptyState.setFont(Theme.font(Font.PLAIN, 15f));
        bind(() -> productsEmptyState.setForeground(Theme.muted()));
        productsResults.setOpaque(false);
        JPanel emptyStatePanel = new JPanel(new GridBagLayout());
        emptyStatePanel.setOpaque(false);
        emptyStatePanel.add(productsEmptyState);
        productsResults.add(styledScroll(productsTable, 46), "products");
        productsResults.add(emptyStatePanel, "empty");

        productSearchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                applyProductFilters();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                applyProductFilters();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                applyProductFilters();
            }
        });
        productCategoryFilter.addActionListener(event -> applyProductFilters());
        clearFilters.addActionListener(event -> {
            productSearchField.setText("");
            productCategoryFilter.setSelectedItem("Todas");
        });

        page.add(tableCard(cardHeader, productsResults), BorderLayout.CENTER);
        productsTable.getColumnModel().getColumn(6)
                .setCellRenderer(new ChipRenderer(InventoryFrame::statusColors));
        return page;
    }

    private void applyProductFilters() {
        String search = productSearchField.getText().trim().toLowerCase(Locale.ROOT);
        String category = (String) productCategoryFilter.getSelectedItem();
        productsSorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                if (category != null && !"Todas".equals(category)
                        && !category.equals(entry.getStringValue(3))) {
                    return false;
                }
                if (search.isEmpty()) {
                    return true;
                }
                for (int column : new int[] {1, 2, 3, 5}) {
                    if (entry.getStringValue(column).toLowerCase(Locale.ROOT).contains(search)) {
                        return true;
                    }
                }
                return false;
            }
        });
        productsResultsLayout.show(productsResults,
                productsTable.getRowCount() == 0 ? "empty" : "products");
    }

    private void refreshProductCategories() {
        String selectedCategory = (String) productCategoryFilter.getSelectedItem();
        productCategoryFilter.removeAllItems();
        productCategoryFilter.addItem("Todas");
        products.stream()
                .map(Product::category)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(productCategoryFilter::addItem);
        if (selectedCategory != null) {
            productCategoryFilter.setSelectedItem(selectedCategory);
            if (!selectedCategory.equals(productCategoryFilter.getSelectedItem())) {
                productCategoryFilter.setSelectedItem("Todas");
            }
        }
        applyProductFilters();
    }

    private JPanel createMovementsPage() {
        JPanel page = new JPanel(new BorderLayout());
        page.setOpaque(false);

        movementProduct.setFont(Theme.font(Font.PLAIN, 13f));
        movementProduct.setPreferredSize(new Dimension(300, 36));
        movementProduct.setRenderer(productComboRenderer());
        costingMethod.setFont(Theme.font(Font.PLAIN, 13f));
        costingMethod.setPreferredSize(new Dimension(130, 36));
        movementProduct.addActionListener(event -> {
            if (!updatingMovementProducts) {
                loadHistory();
            }
        });
        costingMethod.addActionListener(event -> {
            if (!updatingMovementProducts) {
                loadHistory();
            }
        });

        JButton entry = button("Registrar entrada", true);
        JButton exit = button("Registrar salida", false);
        entry.addActionListener(event -> showMovementDialog(true));
        exit.addActionListener(event -> showMovementDialog(false));
        JButton adjust = null;
        if (currentUser.role() == UserRole.ADMIN) {
            adjust = button("Ajustar existencias", false);
            adjust.addActionListener(event -> showAdjustmentDialog());
        }

        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        filters.setOpaque(false);
        filters.add(mutedLabel("Producto", 13f));
        filters.add(movementProduct);
        filters.add(mutedLabel("Costeo", 13f));
        filters.add(costingMethod);

        JPanel header = new JPanel(new BorderLayout(0, 14));
        header.setOpaque(false);
        header.add(sectionHeader("Movimientos de inventario",
                adjust == null ? buttonRow(entry, exit) : buttonRow(entry, exit, adjust)), BorderLayout.NORTH);
        header.add(filters, BorderLayout.CENTER);

        page.add(tableCard(header, movementsTable, 46, 2, 3, 4), BorderLayout.CENTER);
        movementsTable.getColumnModel().getColumn(1)
                .setCellRenderer(new ChipRenderer(InventoryFrame::movementColors));
        return page;
    }

    private JPanel createLedgerPage() {
        JPanel page = new JPanel(new BorderLayout());
        page.setOpaque(false);

        ledgerCategory.setFont(Theme.font(Font.PLAIN, 13f));
        ledgerCategory.setPreferredSize(new Dimension(220, 36));
        ledgerProduct.setFont(Theme.font(Font.PLAIN, 13f));
        ledgerProduct.setPreferredSize(new Dimension(320, 36));
        ledgerProduct.setRenderer(productComboRenderer());
        ledgerCategory.addActionListener(event -> {
            if (!updatingLedgerFilters) {
                refreshLedgerProducts();
            }
        });
        ledgerProduct.addActionListener(event -> {
            if (!updatingLedgerFilters) {
                loadLedger();
            }
        });

        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        filters.setOpaque(false);
        filters.add(mutedLabel("Categoría", 13f));
        filters.add(ledgerCategory);
        filters.add(mutedLabel("Producto", 13f));
        filters.add(ledgerProduct);

        JPanel header = new JPanel(new BorderLayout(0, 14));
        header.setOpaque(false);
        header.add(sectionHeader("Control de inventario · Promedio ponderado", null), BorderLayout.NORTH);
        header.add(filters, BorderLayout.CENTER);

        page.add(tableCard(header, ledgerTable, 56,
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15), BorderLayout.CENTER);
        return page;
    }

    private JPanel createUsersPage() {
        JPanel page = new JPanel(new BorderLayout());
        page.setOpaque(false);
        JButton add = button("Crear usuario", true);
        JButton toggle = button("Activar / desactivar", false);
        add.addActionListener(event -> showCreateUserDialog());
        toggle.addActionListener(event -> toggleSelectedUser());
        page.add(tableCard(sectionHeader("Cuentas de la aplicación", buttonRow(add, toggle)),
                usersTable, 46), BorderLayout.CENTER);
        usersTable.getColumnModel().getColumn(2)
                .setCellRenderer(new ChipRenderer(InventoryFrame::roleColors));
        usersTable.getColumnModel().getColumn(3)
                .setCellRenderer(new ChipRenderer(InventoryFrame::statusColors));
        usersTable.setAutoCreateRowSorter(true);
        refreshUsers();
        return page;
    }

    // ------------------------------------------------------------ componentes

    private JPanel statCard(String title, JLabel value, Color accent) {
        RoundedPanel card = new RoundedPanel(new BorderLayout(0, 6), 18);
        card.setBorder(BorderFactory.createEmptyBorder(16, 20, 14, 20));
        JPanel caption = new JPanel(new BorderLayout());
        caption.setOpaque(false);
        caption.add(mutedLabel(title, 13f), BorderLayout.WEST);
        JLabel dot = new JLabel("●");
        dot.setForeground(accent);
        caption.add(dot, BorderLayout.EAST);
        value.setFont(Theme.font(Font.BOLD, 30f));
        bind(() -> value.setForeground(Theme.text()));
        card.add(caption, BorderLayout.NORTH);
        card.add(value, BorderLayout.CENTER);
        return card;
    }

    private JPanel chartCard(String title, String subtitle, JComponent body) {
        RoundedPanel card = new RoundedPanel(new BorderLayout(0, 10), 18);
        card.setBorder(BorderFactory.createEmptyBorder(16, 20, 14, 20));
        JPanel heading = new JPanel(new GridLayout(0, 1, 0, 2));
        heading.setOpaque(false);
        heading.add(textLabel(title, Font.BOLD, 16f));
        heading.add(mutedLabel(subtitle, 12f));
        card.add(heading, BorderLayout.NORTH);
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    private JPanel sectionHeader(String title, JComponent right) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.add(textLabel(title, Font.BOLD, 18f), BorderLayout.WEST);
        if (right != null) {
            row.add(right, BorderLayout.EAST);
        }
        return row;
    }

    private JPanel buttonRow(JButton... buttons) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        row.setOpaque(false);
        for (JButton button : buttons) {
            row.add(button);
        }
        return row;
    }

    private JPanel tableCard(JComponent header, JTable table, int headerHeight, int... rightColumns) {
        return tableCard(header, styledScroll(table, headerHeight, rightColumns));
    }

    private JPanel tableCard(JComponent header, JComponent content) {
        RoundedPanel card = new RoundedPanel(new BorderLayout(0, 14), 18);
        card.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
        card.add(header, BorderLayout.NORTH);
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    private JScrollPane styledScroll(JTable table, int headerHeight, int... rightColumns) {
        Set<Integer> right = new TreeSet<>();
        for (int column : rightColumns) {
            right.add(column);
        }
        table.setRowHeight(42);
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(true);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setPreferredSize(new Dimension(0, headerHeight));
        TextRenderer textRenderer = new TextRenderer(right);
        table.setDefaultRenderer(Object.class, textRenderer);
        table.setDefaultRenderer(Number.class, textRenderer);
        table.getTableHeader().setDefaultRenderer(new HeaderRenderer(right));
        JScrollPane scroll = new JScrollPane(table);
        bind(() -> {
            table.setBackground(Theme.surface());
            table.setForeground(Theme.text());
            table.setGridColor(Theme.border());
            table.setSelectionBackground(Theme.selection());
            table.setSelectionForeground(Theme.text());
            scroll.setBorder(BorderFactory.createLineBorder(Theme.border()));
            scroll.getViewport().setBackground(Theme.surface());
        });
        return scroll;
    }

    private JButton button(String text, boolean primary) {
        JButton button = new JButton(text);
        button.putClientProperty("JButton.buttonType", "roundRect");
        button.setFont(Theme.font(Font.BOLD, 13f));
        button.setFocusPainted(false);
        button.setMargin(new Insets(8, 18, 8, 18));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        bind(() -> {
            button.setBackground(primary ? Theme.LIME : Theme.surfaceAlt());
            button.setForeground(primary ? Theme.INK : Theme.text());
        });
        return button;
    }

    private static DefaultListCellRenderer productComboRenderer() {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Product product) {
                    String packageInfo = product.packaged()
                            ? " · " + product.unit() + " de " + product.piecesPerPackage() + " pzas"
                            : " · " + product.stockUnit();
                    setText(product.sku() + " - " + product.name() + packageInfo);
                }
                return this;
            }
        };
    }

    // --------------------------------------------------------- ajustes de usuario

    private void showUserSettings() {
        JDialog settings = new JDialog(this, "Configuración de usuario", true);
        settings.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Mi cuenta", createAccountSettings());
        tabs.addTab("Contraseña", createPasswordSettings());
        tabs.addTab("Apariencia", createAppearanceSettings());
        settings.add(tabs);
        settings.setSize(600, 540);
        settings.setLocationRelativeTo(this);
        settings.setVisible(true);
    }

    private JPanel createAccountSettings() {
        JPanel panel = settingsPanel("Nombre de usuario",
                "Este nombre también se usa para iniciar sesión.");
        JTextField username = new JTextField(currentUser.username(), 24);
        JPasswordField currentPassword = new JPasswordField(24);
        JPanel form = settingsForm();
        form.add(field("Nuevo nombre de usuario", username));
        form.add(field("Confirma con tu contraseña", currentPassword));
        JButton save = button("Guardar nombre", true);
        save.addActionListener(event -> {
            char[] password = currentPassword.getPassword();
            try {
                currentUser = userService.changeUsername(currentUser.id(), username.getText(), password);
                userChip.update(currentUser.username(), roleLabel(currentUser.role()));
                if (currentUser.role() == UserRole.ADMIN) {
                    refreshUsers();
                }
                showMessage("Nombre de usuario actualizado.", JOptionPane.INFORMATION_MESSAGE);
            } catch (IllegalArgumentException | IllegalStateException exception) {
                showMessage(exception.getMessage(), JOptionPane.ERROR_MESSAGE);
            } finally {
                Arrays.fill(password, '\0');
                currentPassword.setText("");
            }
        });
        panel.add(form, BorderLayout.CENTER);
        panel.add(footerRow(save), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createPasswordSettings() {
        JPanel panel = settingsPanel("Cambiar contraseña",
                "La contraseña nueva puede tener cualquier longitud, pero no puede estar vacía.");
        JPasswordField currentPassword = new JPasswordField(24);
        JPasswordField newPassword = new JPasswordField(24);
        JPasswordField confirmation = new JPasswordField(24);
        JPanel form = settingsForm();
        form.add(field("Contraseña actual", currentPassword));
        form.add(field("Contraseña nueva", newPassword));
        form.add(field("Confirmar contraseña nueva", confirmation));
        JButton save = button("Actualizar contraseña", true);
        save.addActionListener(event -> {
            char[] oldValue = currentPassword.getPassword();
            char[] newValue = newPassword.getPassword();
            char[] repeatedValue = confirmation.getPassword();
            try {
                if (!Arrays.equals(newValue, repeatedValue)) {
                    throw new IllegalArgumentException("Las contraseñas nuevas no coinciden.");
                }
                userService.changePassword(currentUser.id(), oldValue, newValue);
                showMessage("Contraseña actualizada.", JOptionPane.INFORMATION_MESSAGE);
            } catch (IllegalArgumentException | IllegalStateException exception) {
                showMessage(exception.getMessage(), JOptionPane.ERROR_MESSAGE);
            } finally {
                Arrays.fill(oldValue, '\0');
                Arrays.fill(newValue, '\0');
                Arrays.fill(repeatedValue, '\0');
                currentPassword.setText("");
                newPassword.setText("");
                confirmation.setText("");
            }
        });
        panel.add(form, BorderLayout.CENTER);
        panel.add(footerRow(save), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createAppearanceSettings() {
        JPanel panel = settingsPanel("Apariencia",
                "Ajusta la pantalla y la asistencia por voz. Estas opciones se guardan en este equipo.");
        JComboBox<String> textSize = new JComboBox<>(new String[] {"100 %", "125 %", "150 %"});
        textSize.setSelectedItem(textScalePercent + " %");
        textSize.setFont(Theme.font(Font.PLAIN, 13f));
        textSize.getAccessibleContext().setAccessibleName("Tamaño del texto");
        textSize.getAccessibleContext().setAccessibleDescription(
                "Aumenta el tamaño del texto de la aplicación.");
        JCheckBox contrast = new JCheckBox("Usar alto contraste", highContrast);
        contrast.setFont(Theme.font(Font.PLAIN, 13f));
        contrast.getAccessibleContext().setAccessibleDescription(
                "Aumenta el contraste de texto, fondos y bordes.");
        JCheckBox motion = new JCheckBox("Reducir animaciones", reducedMotion);
        motion.setFont(Theme.font(Font.PLAIN, 13f));
        motion.getAccessibleContext().setAccessibleDescription(
                "Desactiva las transiciones animadas del menú lateral.");
        narrationToggle = new JCheckBox("Narrar controles al pasar el mouse", mouseNarrationEnabled);
        narrationToggle.setFont(Theme.font(Font.PLAIN, 13f));
        narrationToggle.getAccessibleContext().setAccessibleDescription(
                "Lee en voz alta el nombre del control o el contenido de la celda bajo el puntero.");
        narrationToggle.setEnabled(VoiceNarrator.isSupported());
        narrationToggle.setToolTipText(VoiceNarrator.isSupported()
                ? "La voz anuncia el control bajo el puntero después de una breve pausa."
                : "La narración por voz está disponible en Windows.");
        narrationToggle.addActionListener(event -> setMouseNarrationEnabled(narrationToggle.isSelected()));
        Runnable updateOptions = () -> {
            String selected = (String) textSize.getSelectedItem();
            int scale = selected == null ? 100 : Integer.parseInt(selected.replace(" %", ""));
            updateAccessibilityOptions(scale, contrast.isSelected(), motion.isSelected());
        };
        textSize.addActionListener(event -> updateOptions.run());
        contrast.addActionListener(event -> updateOptions.run());
        motion.addActionListener(event -> updateOptions.run());
        JLabel note = new JLabel("El tema claro u oscuro se cambia con el interruptor de la parte superior.");
        note.setEnabled(false);
        JPanel options = settingsForm();
        contrast.setAlignmentX(Component.LEFT_ALIGNMENT);
        motion.setAlignmentX(Component.LEFT_ALIGNMENT);
        narrationToggle.setAlignmentX(Component.LEFT_ALIGNMENT);
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        options.add(field("Tamaño del texto", textSize));
        options.add(Box.createVerticalStrut(6));
        options.add(contrast);
        options.add(Box.createVerticalStrut(6));
        options.add(motion);
        options.add(Box.createVerticalStrut(12));
        options.add(narrationToggle);
        options.add(Box.createVerticalStrut(8));
        options.add(note);
        panel.add(options, BorderLayout.CENTER);
        return panel;
    }

    private JPanel settingsPanel(String title, String description) {
        JPanel panel = new JPanel(new BorderLayout(0, 18));
        panel.setBorder(BorderFactory.createEmptyBorder(24, 26, 22, 26));
        JPanel heading = new JPanel(new GridLayout(0, 1, 0, 6));
        heading.setOpaque(false);
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(Theme.font(Font.BOLD, 18f));
        JLabel descriptionLabel = new JLabel("<html><div style='width:420px'>" + description + "</div></html>");
        descriptionLabel.setEnabled(false);
        heading.add(titleLabel);
        heading.add(descriptionLabel);
        panel.add(heading, BorderLayout.NORTH);
        return panel;
    }

    private JPanel settingsForm() {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setOpaque(false);
        form.setAlignmentX(Component.LEFT_ALIGNMENT);
        return form;
    }

    private JPanel footerRow(JButton button) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        row.setOpaque(false);
        row.add(button);
        return row;
    }

    // -------------------------------------------------------------------- datos

    private void reload() {
        runTask("Cargando productos y existencias...", () -> {
            List<InventoryService.StockRow> stock = service.stockOverview(CostingMethod.PEPS);
            List<Product> loadedProducts = new ArrayList<>(stock.size());
            List<InventoryRow> rows = new ArrayList<>(stock.size());
            for (InventoryService.StockRow item : stock) {
                loadedProducts.add(item.product());
                rows.add(new InventoryRow(item.product(), item.valuation()));
            }
            return new InventorySnapshot(loadedProducts, rows);
        }, snapshot -> {
            products = snapshot.products();
            clear(inventoryModel);
            clear(productsModel);
            Product previous = (Product) movementProduct.getSelectedItem();
            updatingMovementProducts = true;
            movementProduct.removeAllItems();
            BigDecimal totalValue = BigDecimal.ZERO;
            int active = 0;
            int inStock = 0;
            int outOfStock = 0;
            Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
            for (InventoryRow row : snapshot.rows()) {
                Product product = row.product();
                InventoryValuation valuation = row.valuation();
                totalValue = totalValue.add(valuation.inventoryValue());
                byCategory.merge(product.category(), valuation.inventoryValue(), BigDecimal::add);
                if (product.active()) {
                    active++;
                    if (valuation.quantityOnHand().signum() > 0) {
                        inStock++;
                    } else {
                        outOfStock++;
                    }
                }
                inventoryModel.addRow(new Object[] {
                        product.sku(), product.name(), product.stockUnit(),
                        quantity(valuation.quantityOnHand()),
                        money(valuation.inventoryValue()), product.active() ? "Activo" : "Inactivo"
                });
                productsModel.addRow(new Object[] {
                        product.id(), product.sku(), product.name(), product.category(), product.stockUnit(),
                        product.description() == null ? "" : product.description(),
                        product.active() ? "Activo" : "Inactivo"
                });
                movementProduct.addItem(product);
            }
            if (previous != null) {
                for (int index = 0; index < movementProduct.getItemCount(); index++) {
                    if (movementProduct.getItemAt(index).id() == previous.id()) {
                        movementProduct.setSelectedIndex(index);
                        break;
                    }
                }
            }
            updatingMovementProducts = false;
            refreshProductCategories();

            List<BarChartPanel.Bar> bars = new ArrayList<>();
            byCategory.entrySet().stream()
                    .sorted(Map.Entry.<String, BigDecimal>comparingByValue(Comparator.reverseOrder()))
                    .limit(6)
                    .forEach(entry -> bars.add(new BarChartPanel.Bar(
                            entry.getKey(), entry.getValue().doubleValue(), money(entry.getValue()))));
            categoryChart.setBars(bars);
            stockGauge.setValue(active == 0 ? 0 : (double) inStock / active,
                    inStock + " de " + active + " productos activos");

            refreshLedgerCategories();
            productCount.setText(Integer.toString(products.size()));
            activeCount.setText(Integer.toString(active));
            inventoryValue.setText(money(totalValue));
            outOfStockCount.setText(Integer.toString(outOfStock));
            if (!products.isEmpty()) {
                loadHistory();
            } else {
                clear(movementsModel);
            }
            status.setText("Datos actualizados");
        });
    }

    private void editSelectedProduct() {
        int row = productsTable.getSelectedRow();
        if (row < 0) {
            showMessage("Selecciona un producto para editar.", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        long id = ((Number) productsModel.getValueAt(productsTable.convertRowIndexToModel(row), 0)).longValue();
        products.stream().filter(product -> product.id() == id).findFirst()
                .ifPresent(this::showProductDialog);
    }

    private void showProductDialog(Product existing) {
        JTextField sku = new JTextField(existing == null ? "" : existing.sku());
        JTextField name = new JTextField(existing == null ? "" : existing.name());
        JTextField description = new JTextField(existing == null ? "" : valueOrEmpty(existing.description()));
        JComboBox<String> unit = new JComboBox<>(COMMON_UNITS);
        unit.setEditable(true);
        unit.setSelectedItem(existing == null ? "pza" : existing.unit());
        int initialPieces = existing == null
                ? ("docena".equalsIgnoreCase(String.valueOf(unit.getSelectedItem())) ? 12 : 0)
                : existing.piecesPerPackage();
        JSpinner piecesPerPackage = new JSpinner(new SpinnerNumberModel(initialPieces, 0, 1_000_000, 1));
        JLabel packageHint = new JLabel();
        packageHint.setEnabled(false);
        packageHint.setFont(Theme.font(Font.PLAIN, 11f));
        JLabel packageCaption = new JLabel();
        JPanel packageSize = new JPanel(new BorderLayout(0, 4));
        packageSize.setOpaque(false);
        packageSize.add(field(packageCaption, piecesPerPackage), BorderLayout.CENTER);
        packageSize.add(packageHint, BorderLayout.SOUTH);
        Runnable updatePackageFields = () -> {
            String selectedUnit = String.valueOf(unit.getEditor().getItem()).trim();
            boolean packaged = Product.isPackagedUnit(selectedUnit);
            packageSize.setVisible(packaged);
            if (packaged) {
                String normalizedUnit = selectedUnit.toLowerCase(Locale.ROOT);
                if (existing == null && "docena".equals(normalizedUnit)
                        && ((Number) piecesPerPackage.getValue()).intValue() == 0) {
                    piecesPerPackage.setValue(12);
                }
                packageCaption.setText("Piezas que contiene cada " + normalizedUnit);
                int count = ((Number) piecesPerPackage.getValue()).intValue();
                packageHint.setText(count < 1
                        ? "Indica cuántas piezas contiene cada " + normalizedUnit + "."
                        : "1 " + normalizedUnit + " = " + count
                                + " pza. El inventario se controla en piezas.");
            }
        };
        unit.addActionListener(event -> updatePackageFields.run());
        piecesPerPackage.addChangeListener(event -> updatePackageFields.run());
        if (unit.isEditable() && unit.getEditor().getEditorComponent() instanceof JTextField editor) {
            editor.addActionListener(event -> updatePackageFields.run());
        }
        updatePackageFields.run();
        Set<String> categories = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        products.stream()
                .map(Product::category)
                .filter(value -> value != null && !value.isBlank())
                .forEach(categories::add);
        if (existing != null) {
            categories.add(existing.category());
        }
        if (categories.isEmpty()) {
            categories.add("General");
        }
        JComboBox<String> category = new JComboBox<>(categories.toArray(String[]::new));
        category.setEditable(true);
        category.setSelectedItem(existing == null ? "General" : existing.category());
        JCheckBox active = new JCheckBox("Producto activo", existing == null || existing.active());
        JPanel fields = new JPanel(new GridLayout(0, 1, 5, 5));
        fields.add(field("Clave", sku));
        fields.add(field("Nombre", name));
        fields.add(field("Descripción", description));
        fields.add(field("Unidad", unit));
        fields.add(packageSize);
        fields.add(field("Categoría (elige una o escribe una nueva)", category));
        fields.add(active);
        int answer = JOptionPane.showConfirmDialog(
                this, fields, existing == null ? "Nuevo producto" : "Editar producto",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        ProductDraft draft = new ProductDraft(
                sku.getText(), name.getText(), description.getText(),
                String.valueOf(unit.getEditor().getItem()).trim(),
                String.valueOf(category.getEditor().getItem()).trim(), active.isSelected(),
                Product.isPackagedUnit(String.valueOf(unit.getEditor().getItem()).trim())
                        ? ((Number) piecesPerPackage.getValue()).intValue()
                        : 1);
        if (existing != null && existing.active() && !draft.active()) {
            int confirmation = JOptionPane.showConfirmDialog(
                    this,
                    "¿Desactivar " + existing.name()
                            + "? No se podrán registrar movimientos nuevos mientras esté inactivo.",
                    "Confirmar desactivación",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (confirmation != JOptionPane.YES_OPTION) {
                return;
            }
        }
        runTask(existing == null ? "Guardando producto..." : "Actualizando producto...",
                () -> existing == null
                        ? service.createProduct(draft)
                        : service.updateProduct(existing.id(), draft),
                product -> {
                    status.setText("Producto guardado: " + product.name());
                    reload();
                });
    }

    private void showMovementDialog(boolean entry) {
        Product product = (Product) movementProduct.getSelectedItem();
        if (product == null) {
            showMessage("Primero registra un producto.", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!product.active()) {
            showMessage("No se permiten movimientos para un producto inactivo.", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JTextField quantity = new JTextField();
        JTextField unitCost = new JTextField();
        JTextField date = new JTextField(LocalDate.now(ZoneOffset.UTC).toString());
        JPanel fields = new JPanel(new GridLayout(0, 1, 5, 5));
        fields.add(field("Producto", new JLabel(product.sku() + " · " + product.name())));
        fields.add(field("Cantidad (" + product.unit() + (product.packaged()
                ? "; " + product.piecesPerPackage() + " pza por empaque" : "") + ")", quantity));
        if (entry) {
            fields.add(field("Costo por " + product.stockUnit(), unitCost));
        } else {
            fields.add(field("Método de costeo", new JLabel(String.valueOf(costingMethod.getSelectedItem()))));
        }
        boolean canChooseDate = currentUser.role() == UserRole.ADMIN;
        if (canChooseDate) {
            fields.add(field("Fecha (AAAA-MM-DD)", date));
        } else {
            fields.add(field("Fecha", new JLabel(date.getText() + " (automática)")));
        }
        int answer = JOptionPane.showConfirmDialog(
                this, fields, entry ? "Registrar entrada" : "Registrar salida",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }

        BigDecimal parsedQuantity;
        Instant selectedDate = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        BigDecimal parsedCost = null;
        try {
            parsedQuantity = parseDecimal(quantity.getText(), "La cantidad");
            if (canChooseDate) {
                selectedDate = parseDate(date.getText());
            }
            if (entry) {
                parsedCost = parseDecimal(unitCost.getText(), "El costo unitario");
            }
        } catch (IllegalArgumentException exception) {
            showMessage(exception.getMessage(), JOptionPane.ERROR_MESSAGE);
            return;
        }
        Instant occurredAt = selectedDate;
        if (entry) {
            BigDecimal entryCost = parsedCost;
            runTask("Registrando entrada...", () -> service.registerEntry(
                    product.id(), parsedQuantity, entryCost, occurredAt, currentUser), valuation -> {
                showMessage("Entrada registrada. Existencia: "
                            + valuation.quantityOnHand().toPlainString() + " " + product.stockUnit(),
                        JOptionPane.INFORMATION_MESSAGE);
                reload();
            });
        } else {
            CostingMethod method = (CostingMethod) costingMethod.getSelectedItem();
            runTask("Registrando salida...", () -> service.registerExit(
                    product.id(), parsedQuantity, occurredAt, method, currentUser), result -> {
                showMessage("Salida registrada. Costo: " + money(result.issueCost())
                        + "\nExistencia: " + result.valuation().quantityOnHand().toPlainString()
                        + " " + product.stockUnit(), JOptionPane.INFORMATION_MESSAGE);
                reload();
            });
        }
    }

    private void showAdjustmentDialog() {
        if (currentUser.role() != UserRole.ADMIN) {
            showMessage("Solo los administradores pueden ajustar existencias.", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Product product = (Product) movementProduct.getSelectedItem();
        if (product == null) {
            showMessage("Primero registra un producto.", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!product.active()) {
            showMessage("No se permiten ajustes para un producto inactivo.", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JTextField countedQuantity = new JTextField();
        JTextField cost = new JTextField();
        JTextField reason = new JTextField();
        JPanel fields = new JPanel(new GridLayout(0, 1, 5, 5));
        fields.add(field("Producto", new JLabel(product.sku() + " · " + product.name())));
        fields.add(field("Conteo físico en " + product.unit(), countedQuantity));
        fields.add(field("Costo por " + product.stockUnit()
                + " (solo si aumenta y no hay existencias)", cost));
        fields.add(field("Motivo del ajuste (obligatorio)", reason));
        int answer = JOptionPane.showConfirmDialog(
                this, fields, "Ajustar existencias", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }

        BigDecimal parsedCount;
        BigDecimal parsedCost = null;
        try {
            parsedCount = parseDecimal(countedQuantity.getText(), "El conteo físico");
            if (!cost.getText().isBlank()) {
                parsedCost = parseDecimal(cost.getText(), "El costo unitario");
            }
        } catch (IllegalArgumentException exception) {
            showMessage(exception.getMessage(), JOptionPane.ERROR_MESSAGE);
            return;
        }
        int confirmation = JOptionPane.showConfirmDialog(
                this,
                "Se comparará el conteo físico con la existencia registrada. El ajuste quedará "
                        + "asociado a " + currentUser.username() + ". ¿Continuar?",
                "Confirmar ajuste de inventario",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (confirmation != JOptionPane.YES_OPTION) {
            return;
        }
        BigDecimal unitCost = parsedCost;
        runTask("Aplicando ajuste de inventario...", () -> service.adjustStock(
                product.id(), parsedCount, unitCost, reason.getText(), Instant.now(), currentUser), valuation -> {
            showMessage("Ajuste registrado. Existencia actual: "
                    + valuation.quantityOnHand().toPlainString() + " " + product.stockUnit(),
                    JOptionPane.INFORMATION_MESSAGE);
            reload();
        });
    }

    private void loadHistory() {
        Product product = (Product) movementProduct.getSelectedItem();
        if (product == null) {
            clear(movementsModel);
            return;
        }
        CostingMethod method = (CostingMethod) costingMethod.getSelectedItem();
        runTask("Cargando historial...", () -> {
            List<InventoryMovement> movements = service.history(product.id());
            return new HistorySnapshot(movements, service.valuationOf(movements, method));
        }, history -> {
            clear(movementsModel);
            for (InventoryMovement movement : history.movements()) {
                BigDecimal cost = movement.increasesStock()
                        ? movement.unitCost()
                        : history.valuation().issueCosts().get(movement.id());
                BigDecimal amount = cost.multiply(movement.quantity());
                movementsModel.addRow(new Object[] {
                        movement.occurredAt().atZone(ZoneOffset.UTC).toLocalDate().toString(),
                        movementTypeLabel(movement.type()),
                        (movement.increasesStock() ? "+" : "-")
                                + quantity(movement.quantity()) + " " + product.stockUnit(),
                        money(cost), money(amount),
                        valueOrDefault(movement.actorUsername(), "Sin registro"),
                        valueOrEmpty(movement.reason())
                });
            }
            status.setText("Historial de " + product.name() + " · " + method);
        });
    }

    private void refreshUsers() {
        runTask("Cargando cuentas...", userService::users, users -> {
            clear(usersModel);
            for (AppUser user : users) {
                usersModel.addRow(new Object[] {
                        user.id(),
                        user.username(),
                        roleLabel(user.role()),
                        user.active() ? "Activo" : "Inactivo"
                });
            }
        });
    }

    private void showCreateUserDialog() {
        JTextField username = new JTextField();
        JPasswordField password = new JPasswordField();
        JPasswordField confirmation = new JPasswordField();
        JComboBox<UserRole> role = new JComboBox<>(UserRole.values());
        JPanel fields = new JPanel(new GridLayout(0, 1, 5, 5));
        fields.add(field("Usuario", username));
        fields.add(field("Contraseña", password));
        fields.add(field("Confirmar contraseña", confirmation));
        fields.add(field("Rol", role));
        int answer = JOptionPane.showConfirmDialog(
                this, fields, "Crear cuenta de aplicación",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        char[] enteredPassword = password.getPassword();
        char[] repeatedPassword = confirmation.getPassword();
        try {
            if (!Arrays.equals(enteredPassword, repeatedPassword)) {
                throw new IllegalArgumentException("Las contraseñas no coinciden.");
            }
            UserRole selectedRole = (UserRole) role.getSelectedItem();
            userService.createUser(username.getText(), enteredPassword, selectedRole);
            showMessage("La cuenta fue creada.", JOptionPane.INFORMATION_MESSAGE);
            refreshUsers();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            showMessage(exception.getMessage(), JOptionPane.ERROR_MESSAGE);
        } finally {
            Arrays.fill(enteredPassword, '\0');
            Arrays.fill(repeatedPassword, '\0');
        }
    }

    private void toggleSelectedUser() {
        int selectedRow = usersTable.getSelectedRow();
        if (selectedRow < 0) {
            showMessage("Selecciona una cuenta para cambiar su estado.", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int row = usersTable.convertRowIndexToModel(selectedRow);
        long userId = ((Number) usersModel.getValueAt(row, 0)).longValue();
        String username = (String) usersModel.getValueAt(row, 1);
        boolean currentlyActive = "Activo".equals(usersModel.getValueAt(row, 3));
        int answer = JOptionPane.showConfirmDialog(
                this,
                (currentlyActive ? "¿Desactivar" : "¿Activar") + " la cuenta de " + username + "?",
                "Confirmar cambio",
                JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        runTask("Actualizando cuenta...", () -> {
            userService.setUserActive(userId, !currentlyActive, currentUser.id());
            return null;
        }, ignored -> refreshUsers());
    }

    private static String roleLabel(UserRole role) {
        return role == UserRole.ADMIN ? "Administrador" : "Trabajador";
    }

    private void refreshLedgerCategories() {
        CategoryChoice selected = (CategoryChoice) ledgerCategory.getSelectedItem();
        String selectedCategory = selected == null || selected.all() ? null : selected.name();
        updatingLedgerFilters = true;
        ledgerCategory.removeAllItems();
        ledgerCategory.addItem(CategoryChoice.allCategories());
        products.stream()
                .map(Product::category)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .map(CategoryChoice::category)
                .forEach(ledgerCategory::addItem);
        if (selectedCategory != null) {
            for (int index = 0; index < ledgerCategory.getItemCount(); index++) {
                CategoryChoice choice = ledgerCategory.getItemAt(index);
                if (selectedCategory.equals(choice.name())) {
                    ledgerCategory.setSelectedIndex(index);
                    break;
                }
            }
        }
        updatingLedgerFilters = false;
        refreshLedgerProducts();
    }

    private void refreshLedgerProducts() {
        Product selected = (Product) ledgerProduct.getSelectedItem();
        CategoryChoice category = (CategoryChoice) ledgerCategory.getSelectedItem();
        updatingLedgerFilters = true;
        ledgerProduct.removeAllItems();
        for (Product product : products) {
            if (category == null || category.all() || category.name().equals(product.category())) {
                ledgerProduct.addItem(product);
            }
        }
        if (selected != null) {
            for (int index = 0; index < ledgerProduct.getItemCount(); index++) {
                if (ledgerProduct.getItemAt(index).id() == selected.id()) {
                    ledgerProduct.setSelectedIndex(index);
                    break;
                }
            }
        }
        updatingLedgerFilters = false;
        loadLedger();
    }

    private void loadLedger() {
        if (!"ledger".equals(activePage)) {
            return;
        }
        Product product = (Product) ledgerProduct.getSelectedItem();
        if (product == null) {
            clear(ledgerModel);
            return;
        }
        runTask("Calculando kardex de " + product.name() + "...",
                () -> service.movingAverageLedger(product.id()), rows -> {
                    Product current = (Product) ledgerProduct.getSelectedItem();
                    if (current == null || current.id() != product.id()) {
                        return;
                    }
                    clear(ledgerModel);
                    BigDecimal purchaseQuantity = BigDecimal.ZERO;
                    BigDecimal purchaseTotal = BigDecimal.ZERO;
                    BigDecimal saleQuantity = BigDecimal.ZERO;
                    BigDecimal saleTotal = BigDecimal.ZERO;
                    BigDecimal adjustmentIncreaseQuantity = BigDecimal.ZERO;
                    BigDecimal adjustmentIncreaseTotal = BigDecimal.ZERO;
                    BigDecimal adjustmentDecreaseQuantity = BigDecimal.ZERO;
                    BigDecimal adjustmentDecreaseTotal = BigDecimal.ZERO;
                    InventoryLedgerRow endingBalance = null;
                    for (InventoryLedgerRow row : rows) {
                        if (row.purchaseQuantity() != null) {
                            purchaseQuantity = purchaseQuantity.add(row.purchaseQuantity());
                            purchaseTotal = purchaseTotal.add(row.purchaseTotal());
                        }
                        if (row.saleQuantity() != null) {
                            saleQuantity = saleQuantity.add(row.saleQuantity());
                            saleTotal = saleTotal.add(row.saleTotal());
                        }
                        if (row.adjustmentIncreaseQuantity() != null) {
                            adjustmentIncreaseQuantity = adjustmentIncreaseQuantity.add(
                                    row.adjustmentIncreaseQuantity());
                            adjustmentIncreaseTotal = adjustmentIncreaseTotal.add(
                                    row.adjustmentIncreaseTotal());
                        }
                        if (row.adjustmentDecreaseQuantity() != null) {
                            adjustmentDecreaseQuantity = adjustmentDecreaseQuantity.add(
                                    row.adjustmentDecreaseQuantity());
                            adjustmentDecreaseTotal = adjustmentDecreaseTotal.add(
                                    row.adjustmentDecreaseTotal());
                        }
                        endingBalance = row;
                        ledgerModel.addRow(new Object[] {
                                row.occurredAt().atZone(ZoneOffset.UTC).toLocalDate().toString(),
                                quantityOrBlank(row.purchaseQuantity()),
                                moneyOrBlank(row.purchaseUnitCost()),
                                moneyOrBlank(row.purchaseTotal()),
                                quantityOrBlank(row.saleQuantity()),
                                moneyOrBlank(row.saleUnitCost()),
                                moneyOrBlank(row.saleTotal()),
                                quantityOrBlank(row.adjustmentIncreaseQuantity()),
                                moneyOrBlank(row.adjustmentIncreaseUnitCost()),
                                moneyOrBlank(row.adjustmentIncreaseTotal()),
                                quantityOrBlank(row.adjustmentDecreaseQuantity()),
                                moneyOrBlank(row.adjustmentDecreaseUnitCost()),
                                moneyOrBlank(row.adjustmentDecreaseTotal()),
                                quantity(row.balanceQuantity()),
                                moneyOrBlank(row.balanceUnitCost()),
                                money(row.balanceTotal())
                        });
                    }
                    if (endingBalance == null) {
                        status.setText("El producto no tiene movimientos registrados.");
                        return;
                    }
                    ledgerModel.addRow(new Object[] {
                            "Totales",
                            quantityWithUnit(purchaseQuantity, product.stockUnit()), "", money(purchaseTotal),
                            quantityWithUnit(saleQuantity, product.stockUnit()), "", money(saleTotal),
                            quantityWithUnit(adjustmentIncreaseQuantity, product.stockUnit()),
                            "", money(adjustmentIncreaseTotal),
                            quantityWithUnit(adjustmentDecreaseQuantity, product.stockUnit()),
                            "", money(adjustmentDecreaseTotal),
                            quantityWithUnit(endingBalance.balanceQuantity(), product.stockUnit()),
                            moneyOrBlank(endingBalance.balanceUnitCost()),
                            money(endingBalance.balanceTotal())
                    });
                    status.setText("Kardex de " + product.name() + " · " + product.category());
                });
    }

    // ------------------------------------------------------- utilidades de formulario

    private JPanel field(String label, Component component) {
        return field(new JLabel(label), component);
    }

    private JPanel field(JLabel caption, Component component) {
        JPanel row = new JPanel(new BorderLayout(0, 6));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(3, 0, 7, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 76));
        caption.setFont(Theme.font(Font.BOLD, 12f));
        caption.setLabelFor(component);
        if (component.getAccessibleContext() != null
                && component.getAccessibleContext().getAccessibleName() == null) {
            component.getAccessibleContext().setAccessibleName(caption.getText());
        }
        row.add(caption, BorderLayout.NORTH);
        if (component instanceof JTextField textField) {
            textField.setPreferredSize(new Dimension(300, 36));
            textField.setMinimumSize(new Dimension(120, 36));
        } else if (component instanceof JComboBox<?> comboBox) {
            comboBox.setPreferredSize(new Dimension(300, 36));
            comboBox.setMinimumSize(new Dimension(120, 36));
        } else if (component instanceof JLabel value) {
            value.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
        }
        row.add(component, BorderLayout.CENTER);
        return row;
    }

    private <T> void runTask(String message, Task<T> task, Consumer<T> success) {
        status.setText(message);
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return task.run();
            }

            @Override
            protected void done() {
                try {
                    success.accept(get());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showMessage("La operación fue interrumpida.", JOptionPane.ERROR_MESSAGE);
                    status.setText("Operación interrumpida");
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    if (cause != null && (cause instanceof IllegalArgumentException
                            || cause instanceof ProductNotFoundException
                            || cause instanceof InventoryPersistenceException)) {
                        showMessage(cause.getMessage(), JOptionPane.ERROR_MESSAGE);
                    } else {
                        String detail = cause == null || cause.getMessage() == null
                                ? "Error sin detalles."
                                : cause.getMessage();
                        showMessage("No se pudo completar la operación: " + detail, JOptionPane.ERROR_MESSAGE);
                    }
                    status.setText("Ocurrió un error");
                }
            }
        }.execute();
    }

    private void showMessage(String message, int type) {
        JOptionPane.showMessageDialog(this, message, "Sistema de inventario", type);
    }

    private static DefaultTableModel tableModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int column) {
                return "ID".equals(getColumnName(column)) ? Long.class : Object.class;
            }
        };
    }

    private static void clear(DefaultTableModel model) {
        model.setRowCount(0);
    }

    private static String money(BigDecimal amount) {
        return String.format(Locale.US, "$%,.2f", amount);
    }

    private static String moneyOrBlank(BigDecimal amount) {
        return amount == null ? "" : money(amount);
    }

    private static String quantityOrBlank(BigDecimal quantity) {
        return quantity == null ? "" : quantity(quantity);
    }

    private static String quantityWithUnit(BigDecimal quantity, String unit) {
        return quantity(quantity) + " " + unit;
    }

    private static String quantity(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static BigDecimal parseDecimal(String value, String label) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " debe ser un número decimal válido.");
        }
    }

    private static Instant parseDate(String value) {
        try {
            return LocalDate.parse(value.trim()).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("La fecha debe tener el formato AAAA-MM-DD.");
        }
    }

    private interface Task<T> {
        T run() throws Exception;
    }

    private record InventoryRow(Product product, InventoryValuation valuation) {
    }

    private record InventorySnapshot(List<Product> products, List<InventoryRow> rows) {
        private InventorySnapshot {
            products = List.copyOf(products);
            rows = List.copyOf(rows);
        }
    }

    private record HistorySnapshot(List<InventoryMovement> movements, InventoryValuation valuation) {
    }

    private record CategoryChoice(String name, boolean all) {
        private static CategoryChoice allCategories() {
            return new CategoryChoice("Todas las categorías", true);
        }

        private static CategoryChoice category(String name) {
            return new CategoryChoice(name, false);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    // ---------------------------------------------------- colores de las etiquetas

    private static Color[] statusColors(String value) {
        return "Activo".equalsIgnoreCase(value) ? Theme.positive() : Theme.neutral();
    }

    private static Color[] roleColors(String value) {
        return "Administrador".equalsIgnoreCase(value) ? Theme.purple() : Theme.info();
    }

    private static Color[] movementColors(String value) {
        return "Entrada".equalsIgnoreCase(value) || "Ajuste de aumento".equalsIgnoreCase(value)
                ? Theme.positive()
                : Theme.negative();
    }

    private static String movementTypeLabel(MovementType type) {
        return switch (type) {
            case ENTRY -> "Entrada";
            case EXIT -> "Salida";
            case ADJUSTMENT_INCREASE -> "Ajuste de aumento";
            case ADJUSTMENT_DECREASE -> "Ajuste de reducción";
        };
    }

    // ------------------------------------------------------------- renderers

    private static final class TextRenderer extends DefaultTableCellRenderer {
        private final Set<Integer> rightColumns;
        private final Font plain = Theme.font(Font.PLAIN, 13f);
        private final Font bold = Theme.font(Font.BOLD, 13f);

        private TextRenderer(Set<Integer> rightColumns) {
            this.rightColumns = rightColumns;
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean selected, boolean focused, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, false, row, column);
            boolean total = row < table.getRowCount()
                    && "Totales".equals(String.valueOf(table.getValueAt(row, 0)));
            setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 14));
            setHorizontalAlignment(rightColumns.contains(column) ? SwingConstants.RIGHT : SwingConstants.LEFT);
            setOpaque(true);
            setBackground(selected ? Theme.selection() : total ? Theme.surfaceAlt() : Theme.surface());
            setForeground(Theme.text());
            setFont(total ? bold : plain);
            return this;
        }
    }

    private static final class HeaderRenderer extends DefaultTableCellRenderer {
        private final Set<Integer> rightColumns;
        private final Font font = Theme.font(Font.BOLD, 12f);

        private HeaderRenderer(Set<Integer> rightColumns) {
            this.rightColumns = rightColumns;
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean selected, boolean focused, int row, int column) {
            super.getTableCellRendererComponent(table, value, false, false, row, column);
            setOpaque(true);
            setBackground(Theme.surfaceAlt());
            setForeground(Theme.muted());
            setFont(font);
            setHorizontalAlignment(rightColumns.contains(column) ? SwingConstants.RIGHT : SwingConstants.LEFT);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.border()),
                    BorderFactory.createEmptyBorder(0, 14, 0, 14)));
            return this;
        }
    }

    private static final class ChipRenderer implements TableCellRenderer {
        private final Function<String, Color[]> colors;
        private final JPanel panel = new JPanel(new GridBagLayout());
        private final Chip chip = new Chip();

        private ChipRenderer(Function<String, Color[]> colors) {
            this.colors = colors;
            panel.setOpaque(true);
            panel.add(chip);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean selected, boolean focused, int row, int column) {
            String text = value == null ? "" : value.toString();
            Color[] pair = colors.apply(text);
            chip.set(text, pair[0], pair[1]);
            panel.setBackground(selected ? Theme.selection() : Theme.surface());
            return panel;
        }
    }

    private static final class Chip extends JLabel {
        private Color background = Color.LIGHT_GRAY;

        private Chip() {
            setOpaque(false);
            setFont(Theme.font(Font.BOLD, 12f));
            setBorder(BorderFactory.createEmptyBorder(5, 14, 5, 14));
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        private void set(String text, Color background, Color foreground) {
            setText(text);
            this.background = background;
            setForeground(foreground);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            Theme.smooth(g2);
            g2.setColor(background);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g2.dispose();
            super.paintComponent(graphics);
        }
    }

    // ----------------------------------------------------- botones personalizados

    private static final class NavButton extends JButton {
        private boolean active;
        private boolean hover;

        private NavButton(String text) {
            super(text);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setHorizontalAlignment(SwingConstants.LEFT);
            setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 12));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent event) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    hover = false;
                    repaint();
                }
            });
            setActive(false);
        }

        private void setActive(boolean value) {
            active = value;
            setForeground(value ? Theme.INK : Theme.sidebarText());
            setFont(Theme.font(value ? Font.BOLD : Font.PLAIN, 14f));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            Theme.smooth(g2);
            if (active) {
                g2.setColor(Theme.LIME);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
            } else if (hover) {
                g2.setColor(Theme.sidebarHover());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
            }
            g2.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class HamburgerButton extends JButton {
        private float expandedProgress = 1f;
        private boolean hover;
        private Timer iconAnimation;

        private HamburgerButton() {
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setPreferredSize(new Dimension(40, 40));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Contraer menú");
            getAccessibleContext().setAccessibleName("Contraer menú");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent event) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    hover = false;
                    repaint();
                }
            });
        }

        private void setExpanded(boolean expanded) {
            if (iconAnimation != null && iconAnimation.isRunning()) {
                iconAnimation.stop();
            }
            float target = expanded ? 1f : 0f;
            if (Theme.isReducedMotion()) {
                expandedProgress = target;
                repaint();
                return;
            }
            iconAnimation = new Timer(15, event -> {
                float difference = target - expandedProgress;
                if (Math.abs(difference) <= 0.12f) {
                    expandedProgress = target;
                    iconAnimation.stop();
                } else {
                    expandedProgress += Math.signum(difference) * 0.12f;
                }
                repaint();
            });
            iconAnimation.start();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            Theme.smooth(g2);
            g2.setColor(hover ? Theme.sidebarHover() : Theme.SIDEBAR_BG);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            g2.setColor(new Color(255, 255, 255, hover ? 50 : 28));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            float chevronProgress = 1f - expandedProgress;
            Color sidebarText = Theme.sidebarText();
            g2.setColor(new Color(sidebarText.getRed(), sidebarText.getGreen(),
                    sidebarText.getBlue(), Math.round(255 * expandedProgress)));
            g2.drawLine(cx - 7, cy - 5, cx + 7, cy - 5);
            g2.drawLine(cx - 7, cy, cx + 7, cy);
            g2.drawLine(cx - 7, cy + 5, cx + 7, cy + 5);
            g2.setColor(new Color(Theme.LIME.getRed(), Theme.LIME.getGreen(),
                    Theme.LIME.getBlue(), Math.round(255 * chevronProgress)));
            g2.drawLine(cx - 4, cy - 6, cx + 3, cy);
            g2.drawLine(cx + 3, cy, cx - 4, cy + 6);
            g2.dispose();
        }
    }

    private static final class ThemeToggle extends JButton {
        private ThemeToggle() {
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setPreferredSize(new Dimension(72, 36));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Cambiar entre modo claro y oscuro");
            getAccessibleContext().setAccessibleName("Cambiar modo claro u oscuro");
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            Theme.smooth(g2);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(Theme.surface());
            g2.fillRoundRect(0, 0, w - 1, h - 1, h, h);
            g2.setColor(Theme.border());
            g2.drawRoundRect(0, 0, w - 1, h - 1, h, h);
            int knob = h - 8;
            boolean dark = Theme.isDark();
            int knobX = dark ? w - 4 - knob : 4;
            g2.setColor(Theme.LIME);
            g2.fillOval(knobX, 4, knob, knob);
            double sunX = 4 + knob / 2.0;
            double moonX = w - 4 - knob / 2.0;
            double cy = h / 2.0;
            if (dark) {
                Theme.drawSun(g2, sunX, cy, Theme.muted());
                Theme.drawMoon(g2, moonX, cy, Theme.INK);
            } else {
                Theme.drawSun(g2, sunX, cy, Theme.INK);
                Theme.drawMoon(g2, moonX, cy, Theme.muted());
            }
            g2.dispose();
        }
    }

    private static final class UserChip extends JButton {
        private String name;
        private String role;

        private UserChip(String name, String role) {
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setPreferredSize(new Dimension(220, 48));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            getAccessibleContext().setAccessibleName("Configuración de usuario");
            update(name, role);
        }

        private void update(String newName, String newRole) {
            name = newName;
            role = newRole;
            setToolTipText("Configuración de " + newName + " (" + newRole + ")");
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            Theme.smooth(g2);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(Theme.surface());
            g2.fillRoundRect(0, 0, w - 1, h - 1, 16, 16);
            g2.setColor(Theme.border());
            g2.drawRoundRect(0, 0, w - 1, h - 1, 16, 16);
            int avatar = h - 14;
            g2.setColor(Theme.LIME);
            g2.fillOval(7, 7, avatar, avatar);
            g2.setColor(Theme.INK);
            g2.setFont(Theme.font(Font.BOLD, 15f));
            FontMetrics initialMetrics = g2.getFontMetrics();
            String initial = name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
            g2.drawString(initial, 7 + (avatar - initialMetrics.stringWidth(initial)) / 2,
                    7 + (avatar + initialMetrics.getAscent() - initialMetrics.getDescent()) / 2);
            int textX = 7 + avatar + 10;
            int textWidth = w - textX - 12;
            g2.setFont(Theme.font(Font.BOLD, 13f));
            g2.setColor(Theme.text());
            g2.drawString(Theme.ellipsize(name, g2.getFontMetrics(), textWidth), textX, h / 2 - 2);
            g2.setFont(Theme.font(Font.PLAIN, 11f));
            g2.setColor(Theme.muted());
            g2.drawString(Theme.ellipsize(role, g2.getFontMetrics(), textWidth), textX, h / 2 + 13);
            g2.dispose();
        }
    }
}