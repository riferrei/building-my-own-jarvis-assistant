package com.riferrei.myjarvis.extensions;

import dev.langchain4j.data.message.*;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.riferrei.myjarvis.helpers.MessageHelper.messageContent;

public class WorkingMemoryStore implements ChatMemoryStore {

    private static final Logger logger = LoggerFactory.getLogger(WorkingMemoryStore.class);

    private static final String SESSION_ID_ATTRIBUTE = "sessionId";
    private static final String EVENT_ID_ATTRIBUTE = "eventId";
    private static final String ROLE_ATTRIBUTE = "role";
    private static final String TEXT_ATTRIBUTE = "text";
    private static final String ACTOR_ID_ATTRIBUTE = "actorId";
    private static final String CREATED_AT_ATTRIBUTE = "createdAt";
    private static final String EXPIRES_AT_ATTRIBUTE = "expiresAt";

    private static final String EVENT_ID_FORMAT = "%019d#%s";

    private static final int BATCH_WRITE_MAX = 25;

    private DynamoDbClient dynamoDbClient;
    private String tableName;
    private int ttlMinutes;
    private boolean storeSystemMessages = false;
    private boolean storeAiMessages = false;
    private boolean storeToolMessages = false;
    private int maxContextWindow = 1000;

    private int lastFetchedCount = 0;

    private record SessionEvent(String role, String text) {
    }

    private static String sanitizeSessionId(String sessionId) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(sessionId.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        var sanitizedId = sanitizeSessionId(memoryId.toString());
        var chatMessages = new ArrayList<ChatMessage>();

        for (SessionEvent event : fetchSessionEvents(sanitizedId)) {
            var role = event.role();
            var text = event.text();

            if (text == null || text.isBlank()) continue;

            if (!storeSystemMessages && "SYSTEM".equalsIgnoreCase(role)) continue;
            if (!storeAiMessages && "ASSISTANT".equalsIgnoreCase(role)) continue;
            if (!storeToolMessages && "TOOL".equalsIgnoreCase(role)) continue;

            ChatMessage chatMessage = switch (role.toUpperCase()) {
                case "USER" -> UserMessage.from(text);
                case "ASSISTANT" -> AiMessage.from(text);
                case "SYSTEM" -> SystemMessage.from(text);
                default -> {
                    logger.warn("Unknown message role: {}", role);
                    yield null;
                }
            };

            if (chatMessage != null) {
                chatMessages.add(chatMessage);
            }
        }

        var trimmed = chatMessages.size() > maxContextWindow
                ? chatMessages.subList(chatMessages.size() - maxContextWindow, chatMessages.size())
                : chatMessages;

        lastFetchedCount = trimmed.size();
        return new ArrayList<>(trimmed);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> list) {
        var sanitizedId = sanitizeSessionId(memoryId.toString());

        var newMessages = list.size() > lastFetchedCount
                ? list.subList(lastFetchedCount, list.size())
                : List.<ChatMessage>of();

        for (var message : newMessages) {
            if (!storeSystemMessages && message instanceof SystemMessage) continue;
            if (!storeAiMessages && message instanceof AiMessage) continue;
            if (!storeToolMessages && message instanceof ToolExecutionResultMessage) continue;

            String role = switch (message) {
                case UserMessage ignored -> "USER";
                case AiMessage ignored -> "ASSISTANT";
                case SystemMessage ignored -> "SYSTEM";
                default -> null;
            };

            if (role == null) continue;

            String actorId = "USER".equals(role) ? sanitizedId : "assistant";
            String text = messageContent(message);
            if (text == null || text.isBlank()) continue;

            addSessionEvent(sanitizedId, actorId, role, text, System.currentTimeMillis());
        }

        lastFetchedCount = list.size();
    }

    @Override
    public void deleteMessages(Object memoryId) {
        var sanitizedId = sanitizeSessionId(memoryId.toString());
        deleteSessionEvents(sanitizedId);
    }

    private List<SessionEvent> fetchSessionEvents(String sanitizedSessionId) {
        var nowMillis = System.currentTimeMillis();

        try {
            var response = dynamoDbClient.query(builder -> builder
                    .tableName(tableName)
                    .keyConditionExpression("#sid = :sid")
                    .expressionAttributeNames(Map.of("#sid", SESSION_ID_ATTRIBUTE))
                    .expressionAttributeValues(Map.of(":sid", AttributeValue.fromS(sanitizedSessionId)))
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
            return events;
        } catch (Exception ex) {
            logger.error("Error fetching session memory for: {}", sanitizedSessionId, ex);
            return List.of();
        }
    }

    private void addSessionEvent(String sanitizedSessionId,
                                 String actorId,
                                 String role,
                                 String text,
                                 long createdAt) {
        var expiresAt = createdAt / 1000L + (long) ttlMinutes * 60L;
        var eventId = String.format(EVENT_ID_FORMAT, createdAt, UUID.randomUUID());

        try {
            dynamoDbClient.putItem(builder -> builder
                    .tableName(tableName)
                    .item(Map.of(
                            SESSION_ID_ATTRIBUTE, AttributeValue.fromS(sanitizedSessionId),
                            EVENT_ID_ATTRIBUTE, AttributeValue.fromS(eventId),
                            ROLE_ATTRIBUTE, AttributeValue.fromS(role),
                            TEXT_ATTRIBUTE, AttributeValue.fromS(text),
                            ACTOR_ID_ATTRIBUTE, AttributeValue.fromS(actorId),
                            CREATED_AT_ATTRIBUTE, AttributeValue.fromN(Long.toString(createdAt)),
                            EXPIRES_AT_ATTRIBUTE, AttributeValue.fromN(Long.toString(expiresAt))
                    ))
            );
            logger.debug("Session event added for: {}", sanitizedSessionId);
        } catch (Exception ex) {
            logger.error("Error adding session event for: {}", sanitizedSessionId, ex);
        }
    }

    private void deleteSessionEvents(String sanitizedSessionId) {
        try {
            var response = dynamoDbClient.query(builder -> builder
                    .tableName(tableName)
                    .keyConditionExpression("#sid = :sid")
                    .projectionExpression("#sid, #eid")
                    .expressionAttributeNames(Map.of("#sid", SESSION_ID_ATTRIBUTE, "#eid", EVENT_ID_ATTRIBUTE))
                    .expressionAttributeValues(Map.of(":sid", AttributeValue.fromS(sanitizedSessionId)))
            );

            if (response.items().isEmpty()) {
                logger.warn("Session memory not found for: {}", sanitizedSessionId);
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

            logger.info("Successfully deleted session memory for: {}", sanitizedSessionId);
        } catch (Exception ex) {
            logger.error("Error deleting session memory for: {}", sanitizedSessionId, ex);
        }
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

    public boolean isStoreSystemMessages() { return storeSystemMessages; }
    public void setStoreSystemMessages(boolean v) { this.storeSystemMessages = v; }

    public boolean isStoreAiMessages() { return storeAiMessages; }
    public void setStoreAiMessages(boolean v) { this.storeAiMessages = v; }

    public boolean isStoreToolMessages() { return storeToolMessages; }
    public void setStoreToolMessages(boolean v) { this.storeToolMessages = v; }

    public int getMaxContextWindow() { return maxContextWindow; }
    public void setMaxContextWindow(int v) { this.maxContextWindow = v; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private DynamoDbClient dynamoDbClient;
        private String tableName;
        private Integer ttlMinutes;
        private Optional<Boolean> storeSystemMessages = Optional.empty();
        private Optional<Boolean> storeAiMessages = Optional.empty();
        private Optional<Boolean> storeToolMessages = Optional.empty();
        private Optional<Integer> maxContextWindow = Optional.empty();

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

        public Builder storeSystemMessages(boolean value) {
            this.storeSystemMessages = Optional.of(value);
            return this;
        }

        public Builder storeAiMessages(boolean value) {
            this.storeAiMessages = Optional.of(value);
            return this;
        }

        public Builder storeToolMessages(boolean value) {
            this.storeToolMessages = Optional.of(value);
            return this;
        }

        public Builder maxContextWindow(int value) {
            this.maxContextWindow = Optional.of(value);
            return this;
        }

        public WorkingMemoryStore build() {
            Objects.requireNonNull(dynamoDbClient, "dynamoDbClient is required");
            Objects.requireNonNull(tableName, "tableName is required");
            Objects.requireNonNull(ttlMinutes, "ttlMinutes is required");
            var store = new WorkingMemoryStore();
            store.dynamoDbClient = this.dynamoDbClient;
            store.tableName = this.tableName;
            store.ttlMinutes = this.ttlMinutes;
            storeSystemMessages.ifPresent(store::setStoreSystemMessages);
            storeAiMessages.ifPresent(store::setStoreAiMessages);
            storeToolMessages.ifPresent(store::setStoreToolMessages);
            maxContextWindow.ifPresent(store::setMaxContextWindow);
            return store;
        }
    }
}
