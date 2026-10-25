package com.example.inventory.gui;

import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.List;

/** Gráfica de barras horizontales dibujada con Java2D. */
public final class BarChartPanel extends JPanel {
    public record Bar(String label, double value, String display) {
    }

    private List<Bar> bars = List.of();

    public BarChartPanel() {
        setOpaque(false);
        setPreferredSize(new Dimension(320, 180));
    }

    public void setBars(List<Bar> newBars) {
        bars = List.copyOf(newBars);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        Theme.smooth(g2);
        int width = getWidth();
        int height = getHeight();
        g2.setFont(Theme.font(java.awt.Font.PLAIN, 13f));
        FontMetrics metrics = g2.getFontMetrics();
        if (bars.isEmpty()) {
            g2.setColor(Theme.muted());
            String empty = "Sin datos todavía";
            g2.drawString(empty, (width - metrics.stringWidth(empty)) / 2, height / 2);
            g2.dispose();
            return;
        }
        double max = 0;
        for (Bar bar : bars) {
            max = Math.max(max, bar.value());
        }
        if (max <= 0) {
            max = 1;
        }
        int labelWidth = Math.min(140, width / 3);
        int valueWidth = 96;
        int barX = labelWidth + 12;
        int barWidth = Math.max(20, width - barX - valueWidth - 12);
        int rowHeight = Math.min(38, height / bars.size());
        int y = Math.max(0, (height - rowHeight * bars.size()) / 2);
        for (Bar bar : bars) {
            int centerY = y + rowHeight / 2;
            g2.setColor(Theme.text());
            g2.drawString(Theme.ellipsize(bar.label(), metrics, labelWidth),
                    0, centerY + metrics.getAscent() / 2 - 2);
            g2.setColor(Theme.surfaceAlt());
            g2.fillRoundRect(barX, centerY - 6, barWidth, 12, 12, 12);
            int filled = bar.value() <= 0 ? 0 : Math.max(8, (int) Math.round(barWidth * bar.value() / max));
            g2.setColor(Theme.LIME);
            g2.fillRoundRect(barX, centerY - 6, filled, 12, 12, 12);
            g2.setColor(Theme.muted());
            String display = bar.display();
            g2.drawString(display, width - metrics.stringWidth(display),
                    centerY + metrics.getAscent() / 2 - 2);
            y += rowHeight;
        }
        g2.dispose();
    }
}