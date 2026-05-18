import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class SessionRegistry {
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    public void register(String id, Session session) { sessions.put(id, session); }
}
