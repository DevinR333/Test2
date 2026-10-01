package tools;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * java.sql <-> java.time conversions. Android's java.sql.Timestamp/Date lack valueOf(LocalDateTime),
 * from(Instant) and toLocalDateTime(), so the server goes through epoch millis instead.
 */
public final class SqlTime {
    private SqlTime() {}

    public static Timestamp timestamp(Instant instant) {
        return new Timestamp(instant.toEpochMilli());
    }

    public static Timestamp timestamp(LocalDateTime time) {
        return new Timestamp(time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
    }

    public static Date date(LocalDate date) {
        return Date.valueOf(date.toString());
    }

    public static LocalDateTime toLocalDateTime(Timestamp ts) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ts.getTime()), ZoneId.systemDefault());
    }
}
