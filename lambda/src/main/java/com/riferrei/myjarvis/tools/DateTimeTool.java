package com.riferrei.myjarvis.tools;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static com.riferrei.myjarvis.helpers.Constants.TIME_ZONE_PARAM;

public class DateTimeTool {

    private static final Logger logger = LoggerFactory.getLogger(DateTimeTool.class);

    @Tool("Get the current date in user's timezone")
    public String getCurrentDate(InvocationParameters invocationParameters) {
        var timeZone = userTimeZone(invocationParameters);
        logger.info("Getting the current date: timeZone={}", timeZone);
        return LocalDate.now(timeZone).toString();
    }

    @Tool("Get the current date and time in user's timezone in format yyyy-MM-dd'T'HH:mm:ss")
    public String getCurrentDateTime(InvocationParameters invocationParameters) {
        var timeZone = userTimeZone(invocationParameters);
        logger.info("Getting the current date and time: timeZone={}", timeZone);
        var now = LocalDateTime.now(timeZone);
        var formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
        return now.format(formatter);
    }

    @Tool("Get the current day of the week in user's timezone")
    public String getCurrentDayOfWeek(InvocationParameters invocationParameters) {
        var timeZone = userTimeZone(invocationParameters);
        logger.info("Getting the current day of the week: timeZone={}", timeZone);
        return LocalDate.now(timeZone).getDayOfWeek().toString();
    }

    @Tool("Calculate the next occurrence of the specified day of the week")
    public String getNextDayOfWeek(String dayOfWeek, InvocationParameters invocationParameters) {
        var timeZone = userTimeZone(invocationParameters);
        logger.info("Calculating the next day of the week: dayOfWeek={}, timeZone={}", dayOfWeek, timeZone);
        var today = LocalDate.now(timeZone);
        var targetDay = java.time.DayOfWeek.valueOf(dayOfWeek.toUpperCase());
        var daysToAdd = (targetDay.getValue() - today.getDayOfWeek().getValue() + 7) % 7;
        daysToAdd = daysToAdd == 0 ? 7 : daysToAdd;
        var nextDate = today.plusDays(daysToAdd);
        return nextDate.toString();
    }

    @Tool("Calculate datetime by adding specified minutes to current time")
    public String addMinutesToNow(int minutes, InvocationParameters invocationParameters) {
        var timeZone = userTimeZone(invocationParameters);
        logger.info("Adding minutes to now: minutes={}, timeZone={}", minutes, timeZone);
        var future = LocalDateTime.now(timeZone).plusMinutes(minutes);
        var formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
        return future.format(formatter);
    }

    @Tool("Calculate datetime by adding specified hours to current time")
    public String addHoursToNow(int hours, InvocationParameters invocationParameters) {
        var timeZone = userTimeZone(invocationParameters);
        logger.info("Adding hours to now: hours={}, timeZone={}", hours, timeZone);
        var future = LocalDateTime.now(timeZone).plusHours(hours);
        var formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
        return future.format(formatter);
    }

    private static ZoneId userTimeZone(InvocationParameters invocationParameters) {
        String timeZone = invocationParameters.get(TIME_ZONE_PARAM);
        return ZoneId.of(timeZone);
    }
}
