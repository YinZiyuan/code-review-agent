public class SecretConfig {
    public String token() {
        return System.getenv("APP_TOKEN");
    }
}
