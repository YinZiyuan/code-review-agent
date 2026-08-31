public class UserService {
    public String displayName(User user) {
        if (user == null || user.getProfile() == null) {
            return "anonymous";
        }
        return user.getProfile().getDisplayName().trim();
    }
}
