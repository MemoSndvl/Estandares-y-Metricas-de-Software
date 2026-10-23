package com.example.inventory.model;

public record Product(
        long id,
        String sku,
        String name,
        String description,
        String unit,
        String category,
        boolean active,
        int piecesPerPackage) {
    public Product(
            long id,
            String sku,
            String name,
            String description,
            String unit,
            String category,
            boolean active) {
        this(id, sku, name, description, unit, category, active, 1);
    }

    public boolean packaged() {
        return isPackagedUnit(unit);
    }

    public String stockUnit() {
        return packaged() ? "pza" : unit;
    }

    public static boolean isPackagedUnit(String unit) {
        return unit != null && switch (unit.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "caja", "paquete", "bolsa", "docena" -> true;
            default -> false;
        };
    }
}
