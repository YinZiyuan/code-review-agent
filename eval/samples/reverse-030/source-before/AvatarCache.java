import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

public class AvatarCache {
    private final Cache<String, byte[]> cache = Caffeine.newBuilder().maximumSize(10_000).build();

    public void put(String userId, byte[] image) {
        cache.put(userId, image);
    }
}
