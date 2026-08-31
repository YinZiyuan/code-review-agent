public class StatusLabeler {
    public String label(String status) {
        return switch (status) {
            case "OK" -> "ok";
            default -> status.toLowerCase();
        };
    }
}
