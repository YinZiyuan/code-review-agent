public class AuthLogger {
    public void logLogin(String userId, String token) {
        audit("login user=" + userId);
    }
}
