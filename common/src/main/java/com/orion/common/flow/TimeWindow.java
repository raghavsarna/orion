package com.orion.common.flow;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;

/**
 * When a rule (or intent) is active.
 * {@code from}/{@code to}: daily window "HH:mm" (to before from = overnight). Both empty = all day.
 * {@code until}: optional end, ISO instant ("2026-12-31T18:00:00Z") or local date-time ("2026-12-31T18:00").
 */
public record TimeWindow(String from, String to, String until) {

    /** Is a packet arriving at {@code now} inside the window? */
    public boolean isActive(ZonedDateTime now) {
        if (isExpired(now.toInstant(), now.getZone())) {
            return false;
        }
        if (isBlank(from) || isBlank(to)) {
            return true;
        }
        LocalTime t = now.toLocalTime();
        LocalTime f = LocalTime.parse(from.trim());
        LocalTime e = LocalTime.parse(to.trim());
        if (!f.isAfter(e)) {
            return !t.isBefore(f) && t.isBefore(e);
        }
        return !t.isBefore(f) || t.isBefore(e);   // overnight, e.g. 22:00-06:00
    }

    /** Has the {@code until} moment passed? */
    public boolean isExpired(Instant now, ZoneId zone) {
        Instant end = untilInstant(zone);
        return end != null && now.isAfter(end);
    }

    public Instant untilInstant(ZoneId zone) {
        if (isBlank(until)) {
            return null;
        }
        try {
            return Instant.parse(until.trim());
        } catch (DateTimeParseException e) {
            return LocalDateTime.parse(until.trim()).atZone(zone).toInstant();
        }
    }

    /** Returns an error message, or null when the window is well formed. */
    public String validationError() {
        if (isBlank(from) != isBlank(to)) {
            return "time window needs both 'from' and 'to'";
        }
        try {
            if (!isBlank(from)) {
                LocalTime.parse(from.trim());
                LocalTime.parse(to.trim());
            }
        } catch (DateTimeParseException e) {
            return "time must look like 09:00";
        }
        try {
            untilInstant(ZoneId.systemDefault());
        } catch (DateTimeParseException e) {
            return "'until' must look like 2026-12-31T18:00";
        }
        return null;
    }

    public boolean isEmpty() {
        return isBlank(from) && isBlank(to) && isBlank(until);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
