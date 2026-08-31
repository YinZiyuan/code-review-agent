public class UserService {
    public String displayName(User user) {
        return user.getProfile().getDisplayName().trim();
    }
}
