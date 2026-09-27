package com.riferrei.myjarvis.handlers;

import com.amazon.ask.dispatcher.request.handler.HandlerInput;
import com.amazon.ask.dispatcher.request.handler.RequestHandler;
import com.amazon.ask.model.IntentRequest;
import com.amazon.ask.model.Response;
import com.amazon.ask.model.Slot;
import com.amazon.ask.request.Predicates;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riferrei.myjarvis.helpers.HandlerHelper;
import com.riferrei.myjarvis.helpers.RequestContext;
import com.riferrei.myjarvis.services.ChatAssistantService;
import com.riferrei.myjarvis.services.UserMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

import static com.riferrei.myjarvis.helpers.Constants.*;
import static com.riferrei.myjarvis.helpers.HandlerHelper.currentDateTime;
import static com.riferrei.myjarvis.helpers.HandlerHelper.extractRequestContext;
import static com.riferrei.myjarvis.helpers.HandlerHelper.upcomingDates;

public class RememberIntentHandler implements RequestHandler {

    private static final Logger logger = LoggerFactory.getLogger(RememberIntentHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final static String SYSTEM_PROMPT = """
        You are an AI assistant that should act, talk, and behave as if you were J.A.R.V.I.S AI
        from the Iron Man movies. Be formal but friendly, and add personality. You are going to
        be the brains behind an Alexa skill. While providing answers, be informative but maintain
        the J.A.R.V.I.S personality.
        
        As for your specific instructions, The user will ask you to remember memories the user 
        will provide, which will be given to you via prompt. Don't store the memory yourself:
        return it in the JSON below, and it will be stored for you.
        
        The user's timezone is %s, and the current date and time there is %s.
        
        Also, make sure to:
        
        1. Your answer must be in JSON format as specified below.
        2. Only use the context that is relevant to the current query. Don't over do it.
        3. If the user from the context matches the current user, they're the same person.
        4. Don't fabricate answers. Stick with the facts and knowledge from the context.
        5. If the question is not about general topics, then answer based on data you know. 
        6. Keep your answer concise with two sentences top.
        7. Use gender-neutral language - avoid terms like 'sir' or 'madam'.
        
        IMPORTANT DATE CALCULATION:
        The next seven days are: %s.
        When the user mentions a weekday, with or without "next", use that weekday's date
        from this list. Don't calculate weekday dates yourself.

        Analyze the memory for THREE things:
        1. Store confirmation message
        2. The memory to store and whether it is time-bound
        3. Whether it needs a reminder and its details
        
        Your answer MUST return this JSON:
        {
            "answer": "Confirmation message to user",
            "memory": "The memory to store",
            "time_bound": boolean,
            "suggest_reminder": boolean,
            "reminder_topic": "topic",
            "schedule": "YYYY-MM-DDTHH:MM:SS or empty",
            "is_recurring": boolean,
            "frequency": "DAILY/WEEKLY or null",
            "by_days": ["MO"] or null
        }
        
        PS: important, no extra text, only the JSON. Also, the reminder_topic should always
        be filled if suggest_reminder=true, and it must contain a clear, concise topic. It
        should not contain details about the schedule or recurrence.
        
        MEMORY: Write the memory as a concise statement about the user, starting with "User",
        such as "User's new couch will arrive on 2026-10-01." Always use absolute dates
        (YYYY-MM-DD), never relative ones like "tomorrow" or "next Thursday", because the
        memory will be read on later days. Write times the way they're spoken, like "2 PM" or
        "6:57 PM", never as timestamps like "2026-09-27T18:57:27".
        
        TIME-BOUND MEMORIES: Set time_bound=true only when the memory stops being useful once
        its date passes, such as appointments, deliveries, trips, or one-off tasks. Set it to
        false for lasting facts, even when they have a date, such as birthdays, anniversaries,
        or preferences. When time_bound=true, fill schedule with the date and time of the event,
        even if suggest_reminder=false.
        
        REMINDER DETECTION: Set suggest_reminder=true for:
        - Specific time: "at 10 AM", "at noon"
        - Daily recurrence: "every day at X"
        - Weekly recurrence: "every Monday", "every weekday"
        - Hourly recurrence: "every 4 hours", "every 2 hours" (min 1 hour for en-US, 4 hours for others)
        - One-time future: "tomorrow at X", "next Monday"
                
        If suggest_reminder=true, ALSO extract reminder details:
        
        TIME PARSING:
        - "10 a.m." → 10:00:00
        - "10 p.m." → 22:00:00
        - Use :00 for minutes unless specified
        
        RECURRENCE:
        - "every day" → is_recurring: true, frequency: "DAILY"
        - "every Monday" → is_recurring: true, frequency: "WEEKLY", by_days: ["MO"]
        - "every 4 hours" → is_recurring: true, frequency: "HOURLY", interval_hours: 4
        - "every 2 hours" → is_recurring: true, frequency: "HOURLY", interval_hours: 2
        - "in 4 hours" → is_recurring: false (ONE-TIME)
        
        DAY CODES: MO, TU, WE, TH, FR, SA, SU
        
        Few-shot examples:
        
        [Example 1]
        User: "Remember I have a dentist appointment next Tuesday at 2 PM"
        Response: {
            "answer": "Certainly, I've noted your dentist appointment for next Tuesday at 2 PM.",
            "memory": "User has a dentist appointment on 2024-01-09 at 2 PM.",
            "time_bound": true,
            "suggest_reminder": true,
            "reminder_topic": "Dentist appointment",
            "schedule": "2024-01-09T14:00:00",
            "is_recurring": false,
            "frequency": null,
            "by_days": null
        }
        
        [Example 2]
        User: "Remember to take vitamins every morning at 8 AM"
        Response: {
            "answer": "I've recorded your daily vitamin reminder for 8 AM.",
            "memory": "User takes vitamins every morning at 8 AM.",
            "time_bound": false,
            "suggest_reminder": true,
            "reminder_topic": "Take vitamins",
            "schedule": "2024-01-03T08:00:00",
            "is_recurring": true,
            "frequency": "DAILY",
            "by_days": null
        }        
        """;

    private static final String MEMORY_NOT_STORED =
            "I'm sorry, I couldn't store that memory. Please try again.";

    private final ChatAssistantService chatAssistantService;
    private final UserMemoryService userMemoryService;

    public RememberIntentHandler(ChatAssistantService chatAssistantService,
                                 UserMemoryService userMemoryService) {
        this.chatAssistantService = chatAssistantService;
        this.userMemoryService = userMemoryService;
    }

    @Override
    public boolean canHandle(HandlerInput handlerInput) {
        return handlerInput.matches(Predicates.intentName(REMEMBER_INTENT));
    }

    @Override
    public Optional<Response> handle(HandlerInput handlerInput) {
        var context = extractRequestContext(handlerInput);
        var memory = extractMemoryText(handlerInput);

        if (memory.isEmpty()) {
            return buildErrorResponse(handlerInput,
                    "I didn't catch what you wanted me to remember");
        }

        var aiResponse = processWithAI(context, memory.get());

        return aiResponse
                .map(response -> storeMemory(context, memory.get(), response)
                        ? buildResponseFromAI(handlerInput, response)
                        : buildErrorResponse(handlerInput, MEMORY_NOT_STORED))
                .orElseGet(() -> buildFallbackResponse(handlerInput));
    }

    private Optional<String> extractMemoryText(HandlerInput handlerInput) {
        try {
            var intentRequest = (IntentRequest) handlerInput.getRequestEnvelope().getRequest();
            var slot = intentRequest.getIntent().getSlots().get(MEMORY_PARAM);
            return Optional.ofNullable(slot).map(Slot::getValue);
        } catch (Exception e) {
            logger.error("Error extracting memory text", e);
            return Optional.empty();
        }
    }

    private Optional<AnswerResponse> processWithAI(RequestContext requestContext,
                                                   String memory) {
        try {
            var question = String.format("User asked to store this memory: %s", memory);

            var systemPrompt = String.format(SYSTEM_PROMPT,
                    requestContext.timezone(), currentDateTime(requestContext.timezone()),
                    upcomingDates(requestContext.timezone()));

            var response = chatAssistantService.processQueryWithoutContext(
                    systemPrompt,
                    requestContext.userId(),
                    requestContext.timezone(),
                    question
            );

            logger.info("AI response: {}", response);
            return parseResponse(response);
        } catch (Exception ex) {
            logger.error("Error processing with AI", ex);
            return Optional.empty();
        }
    }

    private boolean storeMemory(RequestContext requestContext, String spokenMemory, AnswerResponse aiResponse) {
        var memory = aiResponse.memory() == null || aiResponse.memory().isBlank()
                ? spokenMemory : aiResponse.memory();
        var eventTime = aiResponse.timeBound() && !aiResponse.isRecurring()
                ? parseEventTime(aiResponse.schedule()) : Optional.<LocalDateTime>empty();
        return userMemoryService.saveMemory(requestContext.userId(), memory, eventTime, requestContext.timezone());
    }

    private Optional<LocalDateTime> parseEventTime(String schedule) {
        if (schedule == null || schedule.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(schedule));
        } catch (DateTimeParseException e) {
            logger.warn("Invalid schedule for a time-bound memory: {}", schedule, e);
            return Optional.empty();
        }
    }

    private Optional<Response> buildResponseFromAI(HandlerInput handlerInput, AnswerResponse aiResponse) {
        var speechText = aiResponse.answer();

        if (aiResponse.suggestReminder() && aiResponse.schedule() != null && !aiResponse.schedule().isEmpty()) {
            // Store ALL reminder details in session for YesIntentHandler
            var attributes = handlerInput.getAttributesManager().getSessionAttributes();
            attributes.put("waitingForReminderConfirmation", true);
            attributes.put("reminderTopic", aiResponse.reminderTopic());
            attributes.put("reminderSchedule", aiResponse.schedule());
            attributes.put("reminderIsRecurring", aiResponse.isRecurring());
            attributes.put("reminderFrequency", aiResponse.frequency());
            attributes.put("reminderByDays", aiResponse.byDays());
            handlerInput.getAttributesManager().setSessionAttributes(attributes);

            var promptText = speechText + " Would you like me to set up a reminder for this?";
            return HandlerHelper.buildAlexaResponse(handlerInput, promptText, false);
        }

        return buildSimpleResponse(handlerInput, speechText);
    }

    private Optional<Response> buildSimpleResponse(HandlerInput handlerInput, String speechText) {
        return HandlerHelper.buildAlexaResponse(handlerInput, speechText, true);
    }

    private Optional<Response> buildFallbackResponse(HandlerInput input) {
        var speechText = "I'm sorry, I couldn't process your request to remember that. Please try again.";
        return buildSimpleResponse(input, speechText);
    }

    private Optional<Response> buildErrorResponse(HandlerInput handlerInput, String message) {
        return HandlerHelper.buildAlexaResponse(handlerInput, message, true);
    }

    private Optional<AnswerResponse> parseResponse(String responseAsJson) {
        try {
            return Optional.ofNullable(objectMapper.readValue(extractJsonObject(responseAsJson), AnswerResponse.class));
        } catch (Exception e) {
            logger.error("Failed to parse AI response: {}", responseAsJson, e);
            return Optional.empty();
        }
    }

    private String extractJsonObject(String response) {
        var start = response.indexOf('{');
        var end = response.lastIndexOf('}');
        return (start >= 0 && end > start) ? response.substring(start, end + 1) : response;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AnswerResponse(
            String answer,
            @JsonProperty("memory") String memory,
            @JsonProperty("time_bound") boolean timeBound,
            @JsonProperty("suggest_reminder") boolean suggestReminder,
            @JsonProperty("reminder_topic") String reminderTopic,
            @JsonProperty("schedule") String schedule,
            @JsonProperty("is_recurring") boolean isRecurring,
            @JsonProperty("frequency") String frequency,
            @JsonProperty("by_days") List<String> byDays
    ) {}
}