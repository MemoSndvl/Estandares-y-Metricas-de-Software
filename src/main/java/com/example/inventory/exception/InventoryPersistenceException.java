package com.example.inventory.exception;

public class InventoryPersistenceException extends RuntimeException {
    public InventoryPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
