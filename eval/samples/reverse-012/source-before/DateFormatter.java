import java.text.SimpleDateFormat;
import java.util.Date;

public class DateFormatter {
    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd");
    public String format(Date date) { return FMT.format(date); }
}
