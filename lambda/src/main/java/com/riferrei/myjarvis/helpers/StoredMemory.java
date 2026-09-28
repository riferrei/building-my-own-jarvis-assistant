package com.riferrei.myjarvis.helpers;

public record StoredMemory(
        String id,
        String ownerId,
        String subject,
        String attribute,
        String value,
        String text,
        long createdAt,
        Long expiresAt) {

    public boolean keyed() {
        return attribute != null;
    }

    public boolean timeBound() {
        return expiresAt != null;
    }
}
