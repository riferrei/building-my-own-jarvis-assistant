package com.riferrei.myjarvis.helpers;

import com.amazon.ask.dispatcher.request.handler.HandlerInput;
import com.amazon.ask.model.Response;
import com.amazon.ask.model.services.ServiceException;
import com.amazon.ask.model.services.ups.UpsServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.riferrei.myjarvis.helpers.Constants.*;

public class HandlerHelper {

    private static final Logger logger = LoggerFactory.getLogger(HandlerHelper.class);
    private static final DateTimeFormatter CURRENT_DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH);
    private static final DateTimeFormatter UPCOMING_DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd", Locale.ENGLISH);

    public static String getUserTimeZone(HandlerInput handlerInput) {
        try {
            UpsServiceClient upsClient = handlerInput.getServiceClientFactory().getUpsService();

            String deviceId = handlerInput.getRequestEnvelope()
                    .getContext()
                    .getSystem()
                    .getDevice()
                    .getDeviceId();

            String timeZone = upsClient.getSystemTimeZone(deviceId);

            if (timeZone != null && !timeZone.isEmpty()) {
                logger.info("Retrieved timezone: " + timeZone);
                return timeZone;
            }
        } catch (ServiceException e) {
            logger.error("Failed to get timezone. Status: " + e.getStatusCode(), e);
        } catch (Exception e) {
            logger.error("Unexpected error getting timezone", e);
        }

        return "America/New_York";
    }

    public static String currentDateTime(String timeZone) {
        return ZonedDateTime.now(ZoneId.of(timeZone)).format(CURRENT_DATE_TIME_FORMAT);
    }

    public static String upcomingDates(String timeZone) {
        var today = LocalDate.now(ZoneId.of(timeZone));
        return IntStream.rangeClosed(1, 7)
                .mapToObj(today::plusDays)
                .map(UPCOMING_DATE_FORMAT::format)
                .collect(Collectors.joining(", "));
    }

    public static String extractUserIdFromRequest(HandlerInput handlerInput) {
        var person = handlerInput.getRequestEnvelope().getContext().getSystem().getPerson();
        if (person != null) {
            return person.getPersonId();
        }
        var user = handlerInput.getRequestEnvelope().getContext().getSystem().getUser();
        if (user != null) {
            return user.getUserId();
        }
        return null;
    }

    public static RequestContext extractRequestContext(HandlerInput handlerInput) {
        var sessionId = handlerInput.getRequestEnvelope().getSession().getSessionId();
        var requestAttributes = handlerInput.getAttributesManager().getRequestAttributes();
        var userId = (String) requestAttributes.get(USER_ID_PARAM);
        var userName = (String) requestAttributes.get(USER_NAME_PARAM);
        var timeZone = getUserTimeZone(handlerInput);

        return new RequestContext(sessionId, userId, userName, timeZone);
    }

    public static Optional<Response> buildAlexaResponse(HandlerInput handlerInput,
                                                        String speechText,
                                                        boolean shouldEndSession) {
        return handlerInput.getResponseBuilder()
                .withSpeech(speechText)
                .withSimpleCard(SKILL_NAME, speechText)
                .withShouldEndSession(shouldEndSession)
                .build();
    }
}
