package server.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/******************** Security Layer ********************************

Componenti di protezione della pipeline request.
Implementano anti-abuso per IP, validazione strutturale payload e gestione
credenziali, secondo il flusso documentato in UML_server (/security).

Concorrenza:
- strutture concorrenti per stato per-IP
- approccio lock-free per massimizzare throughput sotto carico

*****************************************************************************/

public final class PasswordHasher {

    private static final int SALT_LENGTH = 16;
    private static final String ALGORITHM = "SHA-256";
    private static final String SEPARATOR = ":";

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    public static byte[] generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        return salt;
    }

    public static String hash(String password, byte[] salt) {
        byte[] digest = computeDigest(password, salt);

        String saltB64 = Base64.getEncoder().encodeToString(salt);
        String hashB64 = Base64.getEncoder().encodeToString(digest);

        return saltB64 + SEPARATOR + hashB64;
    }

    public static boolean verify(String password, String storedHash) {
        if (storedHash == null || !storedHash.contains(SEPARATOR)) {
            return false;
        }

        String[] parts = storedHash.split(SEPARATOR, 2);
        if (parts.length != 2) return false;

        byte[] salt = Base64.getDecoder().decode(parts[0]);
        byte[] expectedHash = Base64.getDecoder().decode(parts[1]);
        byte[] actualHash = computeDigest(password, salt);

        return MessageDigest.isEqual(expectedHash, actualHash);
    }

    private static byte[] computeDigest(String password, byte[] salt) {
        try {
            MessageDigest md = MessageDigest.getInstance(ALGORITHM);
            md.update(salt);
            md.update(password.getBytes(StandardCharsets.UTF_8));
            return md.digest();
        } catch (NoSuchAlgorithmException e) {

            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
