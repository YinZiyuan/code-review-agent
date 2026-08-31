import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public class StreamLoader {
    public long count(Path path) throws Exception {
        Stream<String> lines = Files.lines(path);
        return lines.count();
    }
}
