public class TokenService {
    public String issue(String userId, String ttlMinutes) {
        return sign(userId, Integer.parseInt(ttlMinutes));
    }
}
