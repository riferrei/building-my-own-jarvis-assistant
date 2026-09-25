package com.riferrei.myjarvis.helpers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class OwnerId {

    private OwnerId() {
    }

    public static String sanitize(String userId) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(userId.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString(); // exactly 64 hex chars
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
