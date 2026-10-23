package com.example.inventory.exception;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(long productId) {
        super("No existe un producto con el ID " + productId + ".");
    }
}
