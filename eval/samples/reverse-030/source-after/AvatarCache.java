import java.util.HashMap;
import java.util.Map;

public class AvatarCache {
    private final Map<String, byte[]> cache = new HashMap<>();

    public void put(String userId, byte[] image) {
        cache.put(userId, image);
    }
}
