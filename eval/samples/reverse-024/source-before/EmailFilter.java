import java.util.regex.Pattern;

public class EmailFilter {
    private static final Pattern WORK_EMAIL = Pattern.compile(".+@example\\.com");

    public boolean isWorkEmail(String email) {
        return WORK_EMAIL.matcher(email).matches();
    }
}
