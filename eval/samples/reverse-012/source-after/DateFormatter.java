import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

public class DateFormatter {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    public String format(Date date) { return FMT.format(date.toInstant().atZone(ZoneId.systemDefault())); }
}
