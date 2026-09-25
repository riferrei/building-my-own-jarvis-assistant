package com.riferrei.myjarvis.helpers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Produces the canonical, storage-safe owner identifier used to isolate a
 * user's data. Alexa user/person IDs are far longer than the 64-character
 * limit many backends impose, so we hash them to a fixed 64-char hex string.
 *
 * <p>This is the single source of truth for the isolation key: both the write
 * path (storing a user memory) and the read path (retrieving it) MUST derive
 * the ownerId the same way, or a user could match another user's memories.
 * Keeping the logic here guarantees the two sides cannot drift apart.
 */
public final class OwnerId {

    private OwnerId() {
    }

    /**
     * Hashes an arbitrary user ID to a 64-character hex string (SHA-256).
     */
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
