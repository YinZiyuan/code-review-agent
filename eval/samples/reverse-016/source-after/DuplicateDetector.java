import java.util.HashSet;
import java.util.List;

public class DuplicateDetector {
    public boolean hasDuplicate(List<String> values) {
        return values.size() != new HashSet<>(values).size();
    }
}
