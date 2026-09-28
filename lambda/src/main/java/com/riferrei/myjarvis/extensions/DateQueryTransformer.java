package com.riferrei.myjarvis.extensions;

import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DateQueryTransformer implements QueryTransformer {

    private static final Logger logger = LoggerFactory.getLogger(DateQueryTransformer.class);

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd", Locale.ENGLISH);

    private static final Pattern RELATIVE_DATE = Pattern.compile(
            "\\b(?:(?:this|next)\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b"
                    + "(?!,?\\s*\\d{4}-\\d{2}-\\d{2})"
                    + "|\\b(today|tonight|tomorrow)\\b",
            Pattern.CASE_INSENSITIVE);

    private final ZoneId timeZone;

    private DateQueryTransformer(ZoneId timeZone) {
        this.timeZone = timeZone;
    }

    @Override
    public Collection<Query> transform(Query query) {
        var today = LocalDate.now(timeZone);
        var text = RELATIVE_DATE.matcher(query.text())
                .replaceAll(match -> Matcher.quoteReplacement(DATE_FORMAT.format(resolve(match, today))));
        if (text.equals(query.text())) {
            return List.of(query);
        }
        logger.info("Resolved the dates in the query: {}", text);
        return List.of(query.metadata() == null ? Query.from(text) : Query.from(text, query.metadata()));
    }

    private LocalDate resolve(MatchResult match, LocalDate today) {
        if (match.group(1) != null) {
            var dayOfWeek = DayOfWeek.valueOf(match.group(1).toUpperCase(Locale.ROOT));
            var daysAhead = Math.floorMod(dayOfWeek.getValue() - today.getDayOfWeek().getValue() - 1, 7) + 1;
            return today.plusDays(daysAhead);
        }
        return match.group(2).equalsIgnoreCase("tomorrow") ? today.plusDays(1) : today;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String timeZone;

        public Builder timeZone(String timeZone) {
            this.timeZone = timeZone;
            return this;
        }

        public DateQueryTransformer build() {
            Objects.requireNonNull(timeZone, "timeZone is required");
            return new DateQueryTransformer(ZoneId.of(timeZone));
        }
    }
}
