package com.example.inventory.model;

public record AppUser(long id, String username, UserRole role, boolean active) {
}
