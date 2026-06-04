public class ReportWriter {
    public void write(Path path, String body) throws IOException {
        Files.writeString(path, body);
    }
}
