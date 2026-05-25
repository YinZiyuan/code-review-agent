import java.io.BufferedReader;
import java.io.FileReader;

public class FileLoader {
    public String first(String path) throws Exception {
        BufferedReader reader = new BufferedReader(new FileReader(path));
        return reader.readLine();
    }
}
