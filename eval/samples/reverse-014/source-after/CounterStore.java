import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CounterStore {
    private final Map<String, Integer> counts = new ConcurrentHashMap<>();
    public void inc(String key) { counts.merge(key, 1, Integer::sum); }
}
