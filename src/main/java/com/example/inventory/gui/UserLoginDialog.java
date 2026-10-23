package com.example.inventory.gui;

import com.example.inventory.model.AppUser;
import com.example.inventory.service.UserService;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

public final class UserLoginDialog {
    private UserLoginDialog() {
    }

    public static AppUser authenticate(Component parent, UserService users) {
        boolean setup = !users.hasUsers();
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(owner,
                setup ? "Crear primer administrador" : "Iniciar sesión",
                Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JTextField username = new JTextField();
        JPasswordField password = new JPasswordField();
        JPasswordField confirmation = new JPasswordField();
        username.putClientProperty("JTextField.placeholderText", "Nombre de usuario");
        password.putClientProperty("JTextField.placeholderText", "Contraseña");
        password.putClientProperty("JPasswordField.showRevealButton", true);
        confirmation.putClientProperty("JTextField.placeholderText", "Repite la contraseña");
        confirmation.putClientProperty("JPasswordField.showRevealButton", true);

        JLabel message = new JLabel(" ");
        message.setFont(Theme.font(Font.PLAIN, 12f));
        message.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton submit = new JButton(setup ? "Crear cuenta" : "Iniciar sesión");
        submit.putClientProperty("JButton.buttonType", "roundRect");
        submit.setFont(Theme.font(Font.BOLD, 14f));
        submit.setBackground(Theme.LIME);
        submit.setForeground(Theme.INK);
        submit.setFocusPainted(false);
        submit.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        submit.setMargin(new Insets(12, 24, 12, 24));
        dialog.getRootPane().setDefaultButton(submit);

        AtomicReference<AppUser> result = new AtomicReference<>();

        submit.addActionListener(event -> {
            char[] enteredPassword = password.getPassword();
            char[] repeatedPassword = setup ? confirmation.getPassword() : new char[0];
            if (setup && !Arrays.equals(enteredPassword, repeatedPassword)) {
                Arrays.fill(enteredPassword, '\0');
                Arrays.fill(repeatedPassword, '\0');
                showMessage(message, "Las contraseñas no coinciden.", true);
                return;
            }
            String name = username.getText();
            submit.setEnabled(false);
            showMessage(message, "Verificando...", false);

            new SwingWorker<AppUser, Void>() {
                @Override
                protected AppUser doInBackground() {
                    try {
                        return setup
                                ? users.createInitialAdmin(name, enteredPassword)
                                : users.authenticate(name, enteredPassword);
                    } finally {
                        Arrays.fill(enteredPassword, '\0');
                        Arrays.fill(repeatedPassword, '\0');
                    }
                }

                @Override
                protected void done() {
                    submit.setEnabled(true);
                    try {
                        AppUser user = get();
                        if (user != null) {
                            result.set(user);
                            dialog.dispose();
                            return;
                        }
                        showMessage(message,
                                "Usuario o contraseña incorrectos, o la cuenta está desactivada.", true);
                        password.setText("");
                        password.requestFocusInWindow();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        showMessage(message, "Operación interrumpida.", true);
                    } catch (ExecutionException exception) {
                        Throwable cause = exception.getCause();
                        showMessage(message, cause == null || cause.getMessage() == null
                                ? "Ocurrió un error inesperado." : cause.getMessage(), true);
                    }
                }
            }.execute();
        });

        JLabel title = new JLabel(setup ? "Configuración inicial" : "Bienvenido de nuevo");
        title.setFont(Theme.font(Font.BOLD, 26f));
        title.setForeground(Theme.text());
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel subtitle = new JLabel(setup
                ? "Crea la cuenta del administrador"
                : "Inicia sesión para continuar");
        subtitle.setFont(Theme.font(Font.PLAIN, 14f));
        subtitle.setForeground(Theme.muted());
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBackground(Theme.surface());
        form.setBorder(BorderFactory.createEmptyBorder(44, 48, 36, 48));
        form.add(title);
        form.add(Box.createVerticalStrut(6));
        form.add(subtitle);
        form.add(Box.createVerticalStrut(30));
        form.add(field("Usuario", username));
        form.add(Box.createVerticalStrut(14));
        form.add(field("Contraseña", password));
        if (setup) {
            form.add(Box.createVerticalStrut(14));
            form.add(field("Confirmar contraseña", confirmation));
        }
        form.add(Box.createVerticalStrut(10));
        form.add(message);
        form.add(Box.createVerticalGlue());
        submit.setAlignmentX(Component.LEFT_ALIGNMENT);
        submit.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        form.add(submit);

        JPanel content = new JPanel(new GridLayout(1, 2));
        content.setPreferredSize(new Dimension(820, setup ? 540 : 480));
        content.add(new WelcomePanel());
        content.add(form);

        dialog.setContentPane(content);
        dialog.setResizable(false);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent event) {
                username.requestFocusInWindow();
            }
        });
        dialog.setVisible(true);
        return result.get();
    }

    private static void showMessage(JLabel label, String text, boolean error) {
        label.setForeground(error ? Theme.RED : Theme.muted());
        label.setText(text);
    }

    private static JPanel field(String label, JComponent component) {
        JPanel wrapper = new JPanel(new BorderLayout(0, 6));
        wrapper.setOpaque(false);
        wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 72));
        JLabel caption = new JLabel(label);
        caption.setFont(Theme.font(Font.BOLD, 12f));
        caption.setForeground(Theme.text());
        wrapper.add(caption, BorderLayout.NORTH);
        component.setPreferredSize(new Dimension(290, 40));
        wrapper.add(component, BorderLayout.CENTER);
        return wrapper;
    }

    /** Panel negro con círculos lima, igual que la identidad de la aplicación. */
    private static final class WelcomePanel extends JPanel {
        private WelcomePanel() {
            super(new BorderLayout());
            setOpaque(true);
            setBackground(Theme.SIDEBAR_BG);
            setBorder(BorderFactory.createEmptyBorder(40, 40, 40, 40));
            JPanel text = new JPanel();
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            text.setOpaque(false);
            JLabel logo = new JLabel(new Theme.LogoIcon(44));
            logo.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel name = new JLabel("Inventario");
            name.setFont(Theme.font(Font.BOLD, 30f));
            name.setForeground(Color.WHITE);
            name.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel tagline = new JLabel("<html><div style='width:250px'>Consulta existencias, registra "
                    + "movimientos y controla el costo de tu inventario en un solo lugar.</div></html>");
            tagline.setFont(Theme.font(Font.PLAIN, 14f));
            tagline.setForeground(Theme.SIDEBAR_TEXT);
            tagline.setAlignmentX(Component.LEFT_ALIGNMENT);
            text.add(logo);
            text.add(Box.createVerticalStrut(22));
            text.add(name);
            text.add(Box.createVerticalStrut(12));
            text.add(tagline);
            add(text, BorderLayout.NORTH);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            Theme.smooth(g2);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(new Color(168, 236, 38, 235));
            g2.fillOval(w / 4, h - h / 4, w, w);
            g2.setColor(new Color(168, 236, 38, 70));
            g2.fillOval(-w / 3, h - h / 3, w, w);
            g2.setColor(new Color(255, 255, 255, 18));
            g2.fillOval(w - w / 3, h / 2 - 40, w / 2, w / 2);
            g2.dispose();
        }
    }
}