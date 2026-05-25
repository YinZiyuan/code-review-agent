import java.util.Map;

public class CastReader {
    public Integer read(Map<String, Object> row) {
        Object value = row.get("count");
        return value instanceof Integer count ? count + 1 : 0;
    }
}
