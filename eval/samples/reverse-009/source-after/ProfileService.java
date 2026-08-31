import java.util.List;

public class ProfileService {
    public String firstEmail(List<String> emails) {
        if (emails == null || emails.isEmpty()) return "";
        return emails.get(0).trim();
    }
}
