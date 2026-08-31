import java.util.regex.Pattern;

public class EmailFilter {
    public boolean isWorkEmail(String email) {
        return Pattern.compile(".+@example\\.com").matcher(email).matches();
    }
}
