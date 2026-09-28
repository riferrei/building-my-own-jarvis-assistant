package com.riferrei.myjarvis.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riferrei.myjarvis.helpers.StoredMemory;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.riferrei.myjarvis.helpers.Constants.*;
import static dev.langchain4j.community.store.embedding.dynamodb.DynamoDbEmbeddingStore.DEFAULT_KEY_ATTRIBUTE;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

public class DeDuplicationService {

    private static final Logger logger = LoggerFactory.getLogger(DeDuplicationService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String ATTRIBUTE_PROMPT = """
            You review the long-term memories a personal assistant keeps about a user, looking for
            duplicates. Every memory below is about the same subject and has an attribute that names
            the property it describes. The new memory was saved most recently.

            Decide whether the new memory describes the same property of the subject as one of the old
            memories, only under a different attribute name, so the new memory replaces it. For example,
            favorite_color and color_preference are the same property, and so are birthday and
            date_of_birth. favorite_food and favorite_drink are different properties, and so are
            appointment:dentist and appointment:doctor. Judge the property only; the new memory may hold
            a different value, because it replaces the old one.

            Answer with a JSON object only: {"replaces": 2} when the new memory replaces old memory 2,
            or {"replaces": null} when it replaces none of them. When unsure, answer null.
            """;

    private static final String TEXT_PROMPT = """
            You review the long-term memories a personal assistant keeps about a user, looking for
            duplicates. Every memory below is about the same subject, and none has an attribute, so
            compare their texts. The new memory was saved most recently.

            Decide whether one of the old memories is redundant because every fact it states is also
            stated in the new memory, possibly in different words. An old memory is not redundant when
            it disagrees with the new memory, when it states a fact the new memory lacks, or when the
            two could be about different people or events, such as two children or two appointments.

            Answer with a JSON object only: {"replaces": 2} when old memory 2 is redundant, or
            {"replaces": null} when none of them is. When unsure, answer null.
            """;

    private final BasicChatAssistant memoryJudge;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> userMemoryStore;
    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    private DeDuplicationService(BasicChatAssistant memoryJudge,
                                 EmbeddingModel embeddingModel,
                                 EmbeddingStore<TextSegment> userMemoryStore,
                                 DynamoDbClient dynamoDbClient,
                                 String tableName) {
        this.memoryJudge = memoryJudge;
        this.embeddingModel = embeddingModel;
        this.userMemoryStore = userMemoryStore;
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    public boolean consolidate(StoredMemory memory) {
        if (UNKNOWN_SUBJECT.equals(memory.subject())) {
            logger.info("Skipping memory {} with an unknown subject", memory.id());
            return true;
        }

        long start = System.nanoTime();
        try {
            var candidates = findCandidates(memory);
            if (candidates.isEmpty()) {
                logger.info("No older memory can duplicate memory {}", memory.id());
                return true;
            }

            var replaced = judge(memory, candidates);
            if (replaced.isEmpty()) {
                logger.info("Memory {} replaces none of its {} candidates", memory.id(), candidates.size());
                return true;
            }

            deleteReplaced(memory, replaced.get());
            logger.info("Consolidated memory {} in {} ms", memory.id(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            return true;
        } catch (Exception ex) {
            logger.error("Error consolidating memory {}", memory.id(), ex);
            return false;
        }
    }

    private List<StoredMemory> findCandidates(StoredMemory memory) {
        var request = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingModel.embed(memory.text()).content())
                .maxResults(CONSOLIDATION_SEARCH_LIMIT + 1)
                .filter(metadataKey(OWNER_ID_METADATA_KEY).isEqualTo(memory.ownerId())
                        .and(metadataKey(SUBJECT_METADATA_KEY).isEqualTo(memory.subject())))
                .build();

        return userMemoryStore.search(request).matches().stream()
                .map(this::toStoredMemory)
                .flatMap(Optional::stream)
                .filter(candidate -> isCandidate(memory, candidate))
                .limit(CONSOLIDATION_SEARCH_LIMIT)
                .toList();
    }

    private Optional<StoredMemory> toStoredMemory(EmbeddingMatch<TextSegment> match) {
        var metadata = match.embedded().metadata();
        var createdAt = metadata.getLong(CREATED_AT_METADATA_KEY);
        if (createdAt == null) {
            return Optional.empty();
        }
        return Optional.of(new StoredMemory(
                match.embeddingId(),
                metadata.getString(OWNER_ID_METADATA_KEY),
                metadata.getString(SUBJECT_METADATA_KEY),
                metadata.getString(ATTRIBUTE_METADATA_KEY),
                metadata.getString(VALUE_METADATA_KEY),
                match.embedded().text(),
                createdAt,
                metadata.getLong(EXPIRES_AT_METADATA_KEY)));
    }

    private boolean isCandidate(StoredMemory memory, StoredMemory candidate) {
        if (candidate.id().equals(memory.id())
                || !memory.ownerId().equals(candidate.ownerId())
                || !memory.subject().equals(candidate.subject())
                || candidate.createdAt() >= memory.createdAt()
                || candidate.timeBound() != memory.timeBound()
                || candidate.keyed() != memory.keyed()) {
            return false;
        }
        return !memory.keyed()
                || (!memory.attribute().equals(candidate.attribute())
                    && Objects.equals(memory.value(), candidate.value()));
    }

    private Optional<StoredMemory> judge(StoredMemory memory, List<StoredMemory> candidates) {
        var systemPrompt = memory.keyed() ? ATTRIBUTE_PROMPT : TEXT_PROMPT;
        var answer = memoryJudge.chat(systemPrompt, describe(memory, candidates), new InvocationParameters());
        logger.info("Judged memory {} against {} candidates: {}", memory.id(), candidates.size(), answer);
        return parseReplaces(answer)
                .filter(number -> number >= 1 && number <= candidates.size())
                .map(number -> candidates.get(number - 1));
    }

    private String describe(StoredMemory memory, List<StoredMemory> candidates) {
        var oldMemories = IntStream.range(0, candidates.size())
                .mapToObj(index -> (index + 1) + ". " + describe(candidates.get(index)))
                .collect(Collectors.joining("\n"));
        return "Subject: " + memory.subject()
                + "\n\nNew memory:\n" + describe(memory)
                + "\n\nOld memories:\n" + oldMemories;
    }

    private String describe(StoredMemory memory) {
        if (!memory.keyed()) {
            return "text: " + memory.text();
        }
        var value = memory.value() == null ? "" : ", value: " + memory.value();
        return "attribute: " + memory.attribute() + value + ", text: " + memory.text();
    }

    private Optional<Integer> parseReplaces(String answer) {
        try {
            var replaces = objectMapper.readTree(extractJsonObject(answer)).path("replaces");
            return replaces.isInt() ? Optional.of(replaces.intValue()) : Optional.empty();
        } catch (Exception e) {
            logger.warn("Failed to parse the judge answer: {}", answer, e);
            return Optional.empty();
        }
    }

    private String extractJsonObject(String answer) {
        var start = answer.indexOf('{');
        var end = answer.lastIndexOf('}');
        return (start >= 0 && end > start) ? answer.substring(start, end + 1) : answer;
    }

    private void deleteReplaced(StoredMemory memory, StoredMemory replaced) {
        if (!exists(memory.id())) {
            logger.info("Memory {} no longer exists; keeping memory {}", memory.id(), replaced.id());
            return;
        }
        try {
            dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(DEFAULT_KEY_ATTRIBUTE, AttributeValue.fromS(replaced.id())))
                    .conditionExpression("#createdAt = :createdAt")
                    .expressionAttributeNames(Map.of("#createdAt", CREATED_AT_METADATA_KEY))
                    .expressionAttributeValues(Map.of(":createdAt",
                            AttributeValue.fromN(Long.toString(replaced.createdAt()))))
                    .build());
            logger.info("Deleted memory {} replaced by memory {}: {}", replaced.id(), memory.id(), replaced.text());
        } catch (ConditionalCheckFailedException e) {
            logger.info("Memory {} changed after it was read; keeping it", replaced.id());
        }
    }

    private boolean exists(String id) {
        var response = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(DEFAULT_KEY_ATTRIBUTE, AttributeValue.fromS(id)))
                .projectionExpression("#id")
                .expressionAttributeNames(Map.of("#id", DEFAULT_KEY_ATTRIBUTE))
                .consistentRead(true)
                .build());
        return response.hasItem() && !response.item().isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private BasicChatAssistant memoryJudge;
        private EmbeddingModel embeddingModel;
        private EmbeddingStore<TextSegment> userMemoryStore;
        private DynamoDbClient dynamoDbClient;
        private String tableName;

        public Builder memoryJudge(BasicChatAssistant value) {
            this.memoryJudge = value;
            return this;
        }

        public Builder embeddingModel(EmbeddingModel value) {
            this.embeddingModel = value;
            return this;
        }

        public Builder userMemoryStore(EmbeddingStore<TextSegment> value) {
            this.userMemoryStore = value;
            return this;
        }

        public Builder dynamoDbClient(DynamoDbClient value) {
            this.dynamoDbClient = value;
            return this;
        }

        public Builder tableName(String value) {
            this.tableName = value;
            return this;
        }

        public DeDuplicationService build() {
            Objects.requireNonNull(memoryJudge, "memoryJudge is required");
            Objects.requireNonNull(embeddingModel, "embeddingModel is required");
            Objects.requireNonNull(userMemoryStore, "userMemoryStore is required");
            Objects.requireNonNull(dynamoDbClient, "dynamoDbClient is required");
            Objects.requireNonNull(tableName, "tableName is required");
            return new DeDuplicationService(memoryJudge, embeddingModel, userMemoryStore,
                    dynamoDbClient, tableName);
        }
    }
}
