import java.util.HashMap;
import java.util.Map;

public class SessionRegistry {
    private final Map<String, Session> sessions = new HashMap<>();
    public void register(String id, Session session) { sessions.put(id, session); }
}
