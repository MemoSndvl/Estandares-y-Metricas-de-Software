package com.example.inventory.gui;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Arc2D;

/** Medidor semicircular dibujado con Java2D. */
public final class GaugePanel extends JPanel {
    private double fraction;
    private String caption = "";

    public GaugePanel() {
        setOpaque(false);
        setPreferredSize(new Dimension(320, 180));
    }

    public void setValue(double newFraction, String newCaption) {
        fraction = Math.max(0, Math.min(1, newFraction));
        caption = newCaption == null ? "" : newCaption;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        Theme.smooth(g2);
        int width = getWidth();
        int height = getHeight();
        int thickness = 22;
        int top = thickness / 2 + 6;
        int diameter = Math.min(width - thickness - 16, 2 * (height - top - 54));
        if (diameter > 40) {
            int x = (width - diameter) / 2;
            g2.setStroke(new BasicStroke(thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(Theme.surfaceAlt());
            g2.draw(new Arc2D.Double(x, top, diameter, diameter, 180, -180, Arc2D.OPEN));
            if (fraction > 0) {
                g2.setColor(Theme.LIME);
                g2.draw(new Arc2D.Double(x, top, diameter, diameter, 180, -180 * fraction, Arc2D.OPEN));
            }
            int baseline = top + diameter / 2;
            String percent = Math.round(fraction * 100) + "%";
            g2.setFont(Theme.font(Font.BOLD, 30f));
            FontMetrics big = g2.getFontMetrics();
            g2.setColor(Theme.text());
            g2.drawString(percent, (width - big.stringWidth(percent)) / 2, baseline - 6);
            g2.setFont(Theme.font(Font.PLAIN, 13f));
            FontMetrics small = g2.getFontMetrics();
            g2.setColor(Theme.muted());
            String text = Theme.ellipsize(caption, small, width - 16);
            g2.drawString(text, (width - small.stringWidth(text)) / 2, baseline + 32);
        }
        g2.dispose();
    }
}