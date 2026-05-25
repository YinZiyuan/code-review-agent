import java.nio.file.Files;
import java.nio.file.Path;

public class StreamLoader {
    public long count(Path path) throws Exception {
        try (var lines = Files.lines(path)) {
            return lines.count();
        }
    }
}
