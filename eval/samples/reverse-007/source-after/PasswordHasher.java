import java.security.MessageDigest;

public class PasswordHasher {
    public byte[] hash(String password) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(password.getBytes());
    }
}
