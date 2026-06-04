public class TokenService {
    public String issue(String userId) {
        return sign(userId, 15);
    }
}
