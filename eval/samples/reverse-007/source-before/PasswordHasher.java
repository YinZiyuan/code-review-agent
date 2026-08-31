import java.security.MessageDigest;

public class PasswordHasher {
    public byte[] hash(String password) throws Exception {
        return MessageDigest.getInstance("MD5").digest(password.getBytes());
    }
}
