import java.time.format.DateTimeFormatter;

public class DateRenderer {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    public String render(OffsetDateTime time) {
        return FORMATTER.format(time);
    }
}
