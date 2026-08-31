import java.util.List;

public class DuplicateDetector {
    public boolean hasDuplicate(List<String> values) {
        for (String a : values) for (String b : values) if (a != b && a.equals(b)) return true;
        return false;
    }
}
