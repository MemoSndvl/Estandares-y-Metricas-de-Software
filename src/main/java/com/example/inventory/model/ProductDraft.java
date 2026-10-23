package com.example.inventory.model;

public record ProductDraft(
        String sku,
        String name,
        String description,
        String unit,
        String category,
        boolean active,
        int piecesPerPackage) {
    public ProductDraft(
            String sku,
            String name,
            String description,
            String unit,
            String category,
            boolean active) {
        this(sku, name, description, unit, category, active, 1);
    }
}
