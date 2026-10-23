package com.example.inventory.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Collections;

/** Paleta verde lima / negro / blanco, con modo claro y oscuro. */
public final class Theme {
    public static final Color LIME = new Color(168, 236, 38);
    public static final Color INK = new Color(14, 14, 14);
    public static final Color SIDEBAR_BG = new Color(10, 10, 10);
    public static final Color SIDEBAR_HOVER = new Color(30, 30, 30);
    public static final Color SIDEBAR_TEXT = new Color(196, 196, 196);
    public static final Color SIDEBAR_MUTED = new Color(112, 112, 112);
    public static final Color GREEN = new Color(34, 197, 94);
    public static final Color RED = new Color(239, 68, 68);
    public static final Color ORANGE = new Color(249, 115, 22);
    public static final Color BLUE = new Color(59, 130, 246);

    private static boolean dark;

    private Theme() {
    }

    public static boolean isDark() {
        return dark;
    }

    /** Instala FlatLaf claro u oscuro. Con refreshWindows=true también actualiza las ventanas abiertas. */
    public static void apply(boolean useDark, boolean refreshWindows) {
        dark = useDark;
        FlatLaf.setGlobalExtraDefaults(Collections.singletonMap("@accentColor", "#A8EC26"));
        if (useDark) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }
        UIManager.put("Button.arc", 12);
        UIManager.put("Component.arc", 10);
        UIManager.put("TextComponent.arc", 10);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("TabbedPane.selectedBackground", surface());
        if (refreshWindows) {
            FlatLaf.updateUI();
        }
    }

    public static Color background() {
        return dark ? new Color(18, 18, 18) : new Color(243, 245, 247);
    }

    public static Color surface() {
        return dark ? new Color(28, 28, 30) : Color.WHITE;
    }

    public static Color surfaceAlt() {
        return dark ? new Color(40, 40, 44) : new Color(246, 247, 249);
    }

    public static Color border() {
        return dark ? new Color(56, 56, 60) : new Color(229, 231, 235);
    }

    public static Color text() {
        return dark ? new Color(242, 242, 242) : new Color(17, 24, 39);
    }

    public static Color muted() {
        return dark ? new Color(156, 163, 175) : new Color(107, 114, 128);
    }

    public static Color selection() {
        return dark ? new Color(46, 58, 22) : new Color(236, 250, 204);
    }

    /** {fondo, texto} para etiquetas positivas (Activo, Entrada). */
    public static Color[] positive() {
        return dark
                ? new Color[] {new Color(28, 62, 40), new Color(74, 222, 128)}
                : new Color[] {new Color(220, 252, 231), new Color(22, 128, 61)};
    }

    public static Color[] neutral() {
        return dark
                ? new Color[] {new Color(48, 48, 52), new Color(170, 170, 178)}
                : new Color[] {new Color(240, 241, 243), new Color(107, 114, 128)};
    }

    public static Color[] negative() {
        return dark
                ? new Color[] {new Color(74, 32, 38), new Color(252, 129, 129)}
                : new Color[] {new Color(254, 226, 226), new Color(185, 28, 28)};
    }

    public static Color[] info() {
        return dark
                ? new Color[] {new Color(34, 44, 78), new Color(147, 167, 255)}
                : new Color[] {new Color(224, 231, 255), new Color(67, 56, 202)};
    }

    public static Color[] purple() {
        return dark
                ? new Color[] {new Color(58, 40, 80), new Color(196, 160, 255)}
                : new Color[] {new Color(243, 232, 255), new Color(126, 34, 206)};
    }

    public static Font font(int style, float size) {
        Font base = UIManager.getFont("Label.font");
        if (base == null) {
            base = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        }
        return base.deriveFont(style, size);
    }

    public static String ellipsize(String text, FontMetrics metrics, int maxWidth) {
        if (text == null || metrics.stringWidth(text) <= maxWidth) {
            return text == null ? "" : text;
        }
        String cut = text;
        while (cut.length() > 1 && metrics.stringWidth(cut + "…") > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    public static void drawSun(Graphics2D g2, double cx, double cy, Color color) {
        g2.setColor(color);
        g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.fill(new java.awt.geom.Ellipse2D.Double(cx - 3.5, cy - 3.5, 7, 7));
        for (int i = 0; i < 8; i++) {
            double angle = Math.toRadians(i * 45);
            g2.draw(new java.awt.geom.Line2D.Double(
                    cx + Math.cos(angle) * 5.6, cy + Math.sin(angle) * 5.6,
                    cx + Math.cos(angle) * 7.6, cy + Math.sin(angle) * 7.6));
        }
    }

    public static void drawMoon(Graphics2D g2, double cx, double cy, Color color) {
        g2.setColor(color);
        java.awt.geom.Area moon = new java.awt.geom.Area(
                new java.awt.geom.Ellipse2D.Double(cx - 6, cy - 6, 12, 12));
        moon.subtract(new java.awt.geom.Area(
                new java.awt.geom.Ellipse2D.Double(cx - 1.5, cy - 8, 11, 11)));
        g2.fill(moon);
    }

    public static void smooth(Graphics2D g2) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    /** Logo: cuadro lima con una X negra. */
    public static final class LogoIcon implements Icon {
        private final int size;

        public LogoIcon(int size) {
            this.size = size;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            smooth(g2);
            g2.setColor(LIME);
            g2.fillRoundRect(x, y, size, size, size / 3, size / 3);
            g2.setColor(INK);
            g2.setStroke(new BasicStroke(size / 9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int pad = size / 3;
            g2.drawLine(x + pad, y + pad, x + size - pad, y + size - pad);
            g2.drawLine(x + size - pad, y + pad, x + pad, y + size - pad);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }
}