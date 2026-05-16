package dev.langchain4j.example.codereview;

import java.util.HashMap;
import java.util.Map;

public class UserService {

    // TODO: move this to config file later
    private static final String DB_PASSWORD = "admin123";
    private static final String API_KEY = "sk-hardcoded-secret-key";

    private Map<Integer, String> userCache = new HashMap<>();

    public String getUserName(int userId) {
        try {
            String name = userCache.get(userId);
            if (name == null) {
                // simulate DB call
                Thread.sleep(100);
                name = "User_" + userId;
                userCache.put(userId, name);
            }
            return name;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public boolean validateUser(String username, String password) {
        System.out.println("Validating user: " + username + " with password: " + password);
        if (username == null || password == null) {
            return false;
        }
        return username.equals("admin") && password.equals(DB_PASSWORD);
    }

    public void deleteUser(int userId) {
        try {
            userCache.remove(userId);
            // FIXME: also delete from database
        } catch (Exception e) {
            // silently ignore
        }
    }
}
