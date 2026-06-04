public class ReportWriter {
    public void write(Path path, String body) throws IOException {
        if (body == null) {
            return;
        }





        Files.writeString(path, body);
    }
}
