import java.util.List;

public class ProfileService {
    public String firstEmail(List<String> emails) {
        return emails.get(0).trim();
    }
}
