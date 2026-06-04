import java.text.SimpleDateFormat;

public class DateRenderer {
    private static final SimpleDateFormat FORMATTER = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");

    public String render(java.util.Date time) {
        return FORMATTER.format(time);
    }
}
