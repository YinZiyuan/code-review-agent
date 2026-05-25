import java.util.Map;

public class CastReader {
    public Integer read(Map<String, Object> row) {
        return ((Integer) row.get("count")) + 1;
    }
}
