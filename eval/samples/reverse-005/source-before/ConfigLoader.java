public class ConfigLoader {
    public Properties load(Path path) throws IOException {
        InputStream in = Files.newInputStream(path);
        Properties props = new Properties();
        props.load(in);
        return props;
    }
}
