import java.util.List;

public class BlockingReportService {
    public List<String> render(List<String> ids) {
        return ids.stream().map(this::fetchRemote).toList();
    }
    private String fetchRemote(String id) { return id; }
}
