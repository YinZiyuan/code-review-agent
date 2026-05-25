import java.util.HashMap;
import java.util.Map;

public class CounterStore {
    private final Map<String, Integer> counts = new HashMap<>();
    public void inc(String key) { counts.put(key, counts.getOrDefault(key, 0) + 1); }
}
