package ua.rp.chat.auth;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class PasswordHasher {
    private static final int ITERATIONS = 12000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Hashes a plain text password using PBKDF2WithHmacSHA256.
     * Output format is "iterations:salt:hash".
     */
    public static String hash(String password) {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
        
        return ITERATIONS + ":" 
             + Base64.getEncoder().encodeToString(salt) + ":" 
             + Base64.getEncoder().encodeToString(hash);
    }

    /**
     * Verifies a plain text password against a stored hash string.
     * Uses constant-time comparison to prevent timing attacks.
     */
    public static boolean verify(String password, String storedHash) {
        if (storedHash == null || !storedHash.contains(":")) {
            return false;
        }
        
        String[] parts = storedHash.split(":");
        if (parts.length != 3) {
            return false;
        }
        
        try {
            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] hash = Base64.getDecoder().decode(parts[2]);

            byte[] testHash = pbkdf2(password.toCharArray(), salt, iterations, hash.length * 8);
            
            // Constant-time byte array comparison
            int diff = hash.length ^ testHash.length;
            for (int i = 0; i < hash.length && i < testHash.length; i++) {
                diff |= hash[i] ^ testHash[i];
            }
            return diff == 0;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyLength) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLength);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return skf.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Error executing PBKDF2 password hashing", e);
        }
    }
}
