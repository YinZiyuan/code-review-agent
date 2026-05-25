public class StatusLabeler {
    public String label(String status) {
        if (status == null) return "unknown";
        return switch (status) {
            case "OK" -> "ok";
            default -> status.toLowerCase();
        };
    }
}
