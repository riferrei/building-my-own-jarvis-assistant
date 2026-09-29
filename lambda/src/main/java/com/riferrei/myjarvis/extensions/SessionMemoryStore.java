package com.riferrei.myjarvis.extensions;

import dev.langchain4j.data.message.*;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.riferrei.myjarvis.helpers.MessageHelper.messageContent;

public class SessionMemoryStore implements ChatMemoryStore {

    private static final Logger logger = LoggerFactory.getLogger(SessionMemoryStore.class);

    private static final String SESSION_ID_ATTRIBUTE = "sessionId";
    private static final String EVENT_ID_ATTRIBUTE = "eventId";
    private static final String ROLE_ATTRIBUTE = "role";
    private static final String TEXT_ATTRIBUTE = "text";
    private static final String ACTOR_ID_ATTRIBUTE = "actorId";
    private static final String CREATED_AT_ATTRIBUTE = "createdAt";
    private static final String EXPIRES_AT_ATTRIBUTE = "expiresAt";

    private static final String EVENT_ID_FORMAT = "%019d#%s";
    private static final int BATCH_WRITE_MAX = 25;

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;
    private final int ttlMinutes;
    private final boolean storeAiMessages;

    private int lastFetchedCount = 0;

    private record SessionEvent(String role, String text) {
    }

    private SessionMemoryStore(DynamoDbClient dynamoDbClient, String tableName,
                               int ttlMinutes, boolean storeAiMessages) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
        this.ttlMinutes = ttlMinutes;
        this.storeAiMessages = storeAiMessages;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        var sessionId = memoryId.toString();
        var chatMessages = new ArrayList<ChatMessage>();

        for (SessionEvent event : fetchSessionEvents(sessionId)) {
            var role = event.role();
            var text = event.text();

            if (text == null || text.isBlank()) continue;

            if (!storeAiMessages && "ASSISTANT".equalsIgnoreCase(role)) continue;

            ChatMessage chatMessage = switch (role.toUpperCase()) {
                case "USER" -> UserMessage.from(text);
                case "ASSISTANT" -> AiMessage.from(text);
                default -> {
                    logger.warn("Unknown message role: {}", role);
                    yield null;
                }
            };

            if (chatMessage != null) {
                chatMessages.add(chatMessage);
            }
        }

        lastFetchedCount = chatMessages.size();
        return chatMessages;
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> list) {
        var sessionId = memoryId.toString();

        var newMessages = list.size() > lastFetchedCount
                ? list.subList(lastFetchedCount, list.size())
                : List.<ChatMessage>of();

        for (var message : newMessages) {
            if (!storeAiMessages && message instanceof AiMessage) continue;

            String role = switch (message) {
                case UserMessage ignored -> "USER";
                case AiMessage ignored -> "ASSISTANT";
                default -> null;
            };

            if (role == null) continue;

            String actorId = "USER".equals(role) ? sessionId : "assistant";
            String text = messageContent(message);
            if (text == null || text.isBlank()) continue;

            addSessionEvent(sessionId, actorId, role, text, System.currentTimeMillis());
        }

        lastFetchedCount = list.size();
    }

    @Override
    public void deleteMessages(Object memoryId) {
        var sessionId = memoryId.toString();
        deleteSessionEvents(sessionId);
    }

    private List<SessionEvent> fetchSessionEvents(String sessionId) {
        var nowMillis = System.currentTimeMillis();
        long start = System.nanoTime();

        try {
            var response = dynamoDbClient.query(builder -> builder
                    .tableName(tableName)
                    .keyConditionExpression("#sid = :sid")
                    .expressionAttributeNames(Map.of("#sid", SESSION_ID_ATTRIBUTE))
                    .expressionAttributeValues(Map.of(":sid", AttributeValue.fromS(sessionId)))
                    .scanIndexForward(true)
            );

            var events = new ArrayList<SessionEvent>();
            for (var item : response.items()) {
                var expiresAt = parseLong(item.get(EXPIRES_AT_ATTRIBUTE));
                if (expiresAt > 0 && expiresAt * 1000L <= nowMillis) {
                    continue;
                }
                events.add(new SessionEvent(attr(item.get(ROLE_ATTRIBUTE)), attr(item.get(TEXT_ATTRIBUTE))));
            }
            logger.info("Loaded {} session events in {} ms", events.size(), elapsedMillis(start));
            return events;
        } catch (Exception ex) {
            logger.error("Error fetching session memory for: {}", sessionId, ex);
            return List.of();
        }
    }

    private void addSessionEvent(String sessionId,
                                 String actorId,
                                 String role,
                                 String text,
                                 long createdAt) {
        var expiresAt = createdAt / 1000L + (long) ttlMinutes * 60L;
        var eventId = String.format(EVENT_ID_FORMAT, createdAt, UUID.randomUUID());
        long start = System.nanoTime();

        try {
            dynamoDbClient.putItem(builder -> builder
                    .tableName(tableName)
                    .item(Map.of(
                            SESSION_ID_ATTRIBUTE, AttributeValue.fromS(sessionId),
                            EVENT_ID_ATTRIBUTE, AttributeValue.fromS(eventId),
                            ROLE_ATTRIBUTE, AttributeValue.fromS(role),
                            TEXT_ATTRIBUTE, AttributeValue.fromS(text),
                            ACTOR_ID_ATTRIBUTE, AttributeValue.fromS(actorId),
                            CREATED_AT_ATTRIBUTE, AttributeValue.fromN(Long.toString(createdAt)),
                            EXPIRES_AT_ATTRIBUTE, AttributeValue.fromN(Long.toString(expiresAt))
                    ))
            );
            logger.info("Saved {} session event in {} ms", role, elapsedMillis(start));
        } catch (Exception ex) {
            logger.error("Error adding session event for: {}", sessionId, ex);
        }
    }

    private void deleteSessionEvents(String sessionId) {
        try {
            var response = dynamoDbClient.query(builder -> builder
                    .tableName(tableName)
                    .keyConditionExpression("#sid = :sid")
                    .projectionExpression("#sid, #eid")
                    .expressionAttributeNames(Map.of("#sid", SESSION_ID_ATTRIBUTE, "#eid", EVENT_ID_ATTRIBUTE))
                    .expressionAttributeValues(Map.of(":sid", AttributeValue.fromS(sessionId)))
            );

            if (response.items().isEmpty()) {
                logger.warn("Session memory not found for: {}", sessionId);
                return;
            }

            var deleteRequests = new ArrayList<WriteRequest>();
            for (var item : response.items()) {
                deleteRequests.add(WriteRequest.builder()
                        .deleteRequest(DeleteRequest.builder()
                                .key(Map.of(
                                        SESSION_ID_ATTRIBUTE, item.get(SESSION_ID_ATTRIBUTE),
                                        EVENT_ID_ATTRIBUTE, item.get(EVENT_ID_ATTRIBUTE)
                                ))
                                .build())
                        .build());
            }

            for (int i = 0; i < deleteRequests.size(); i += BATCH_WRITE_MAX) {
                var batch = deleteRequests.subList(i, Math.min(i + BATCH_WRITE_MAX, deleteRequests.size()));
                dynamoDbClient.batchWriteItem(builder -> builder
                        .requestItems(Map.of(tableName, batch)));
            }

            logger.info("Successfully deleted session memory for: {}", sessionId);
        } catch (Exception ex) {
            logger.error("Error deleting session memory for: {}", sessionId, ex);
        }
    }

    private static long elapsedMillis(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private static String attr(AttributeValue value) {
        return value == null || value.s() == null ? "" : value.s();
    }

    private static long parseLong(AttributeValue value) {
        if (value == null || value.n() == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.n());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private DynamoDbClient dynamoDbClient;
        private String tableName;
        private Integer ttlMinutes;
        private boolean storeAiMessages = false;

        public Builder dynamoDbClient(DynamoDbClient value) {
            this.dynamoDbClient = value;
            return this;
        }

        public Builder tableName(String value) {
            this.tableName = value;
            return this;
        }

        public Builder ttlMinutes(int value) {
            this.ttlMinutes = value;
            return this;
        }

        public Builder storeAiMessages(boolean value) {
            this.storeAiMessages = value;
            return this;
        }

        public SessionMemoryStore build() {
            Objects.requireNonNull(dynamoDbClient, "dynamoDbClient is required");
            Objects.requireNonNull(tableName, "tableName is required");
            Objects.requireNonNull(ttlMinutes, "ttlMinutes is required");
            return new SessionMemoryStore(dynamoDbClient, tableName, ttlMinutes, storeAiMessages);
        }
    }
}
