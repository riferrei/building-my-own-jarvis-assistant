package com.riferrei.myjarvis;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riferrei.myjarvis.helpers.LatencyListener;
import com.riferrei.myjarvis.helpers.StoredMemory;
import com.riferrei.myjarvis.services.BasicChatAssistant;
import com.riferrei.myjarvis.services.DeDuplicationService;
import dev.langchain4j.community.store.embedding.dynamodb.DynamoDbEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.bedrock.BedrockChatRequestParameters;
import dev.langchain4j.model.bedrock.BedrockTitanEmbeddingModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.VectorDistanceFunction;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.riferrei.myjarvis.helpers.Constants.*;
import static dev.langchain4j.community.store.embedding.dynamodb.DynamoDbEmbeddingStore.DEFAULT_KEY_ATTRIBUTE;
import static dev.langchain4j.community.store.embedding.dynamodb.DynamoDbEmbeddingStore.DEFAULT_TEXT_METADATA_KEY;

public class MemoryDeDupHandler implements RequestStreamHandler {

    private static final Logger logger = LoggerFactory.getLogger(MemoryDeDupHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final ChatModel chatModel = BedrockChatModel.builder()
            .region(Region.of(System.getenv("AWS_REGION")))
            .modelId(BEDROCK_CHAT_MODEL_ID)
            .defaultRequestParameters(BedrockChatRequestParameters.builder()
                    .maxOutputTokens(Integer.parseInt(BEDROCK_CHAT_MAX_TOKENS))
                    .additionalModelRequestField("thinking", Map.of("type", "disabled"))
                    .build())
            .listeners(new LatencyListener())
            .build();

    private static final EmbeddingModel embeddingModel = BedrockTitanEmbeddingModel.builder()
            .region(Region.of(System.getenv("AWS_REGION")))
            .model(EMBEDDING_MODEL_NAME)
            .dimensions(Integer.parseInt(EMBEDDING_DIMENSIONS))
            .normalize(true)
            .build();

    private static final EmbeddingStore<TextSegment> userMemoryStore = DynamoDbEmbeddingStore.builder()
            .region(System.getenv("AWS_REGION"))
            .tableName(DYNAMODB_USER_MEMORY_TABLE_NAME)
            .indexName(DYNAMODB_USER_MEMORY_INDEX_NAME)
            .distanceFunction(VectorDistanceFunction.COSINE)
            .inlineFilterAttributes(List.of(OWNER_ID_METADATA_KEY, SUBJECT_METADATA_KEY))
            .createTableIfNotExists(false)
            .build();

    private static final DynamoDbClient dynamoDbClient = DynamoDbClient.builder()
            .region(Region.of(System.getenv("AWS_REGION")))
            .build();

    private static final DeDuplicationService deDuplicationService = DeDuplicationService.builder()
            .memoryJudge(AiServices.builder(BasicChatAssistant.class)
                    .chatModel(chatModel)
                    .build())
            .embeddingModel(embeddingModel)
            .userMemoryStore(userMemoryStore)
            .dynamoDbClient(dynamoDbClient)
            .tableName(DYNAMODB_USER_MEMORY_TABLE_NAME)
            .build();

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        var batchItemFailures = new ArrayList<Map<String, String>>();
        for (var record : objectMapper.readTree(input).path("Records")) {
            var sequenceNumber = record.path("dynamodb").path("SequenceNumber").asText();
            var memory = toStoredMemory(record.path("dynamodb").path("NewImage"));
            if (memory.isEmpty()) {
                logger.warn("Skipping stream record {} without a complete memory", sequenceNumber);
                continue;
            }
            if (!deDuplicationService.consolidate(memory.get())) {
                batchItemFailures.add(Map.of("itemIdentifier", sequenceNumber));
                break;
            }
        }
        objectMapper.writeValue(output, Map.of("batchItemFailures", batchItemFailures));
    }

    private Optional<StoredMemory> toStoredMemory(JsonNode image) {
        var id = string(image, DEFAULT_KEY_ATTRIBUTE);
        var ownerId = string(image, OWNER_ID_METADATA_KEY);
        var subject = string(image, SUBJECT_METADATA_KEY);
        var text = string(image, DEFAULT_TEXT_METADATA_KEY);
        var createdAt = number(image, CREATED_AT_METADATA_KEY);
        if (id == null || ownerId == null || subject == null || text == null || createdAt == null) {
            return Optional.empty();
        }
        return Optional.of(new StoredMemory(id, ownerId, subject,
                string(image, ATTRIBUTE_METADATA_KEY), string(image, VALUE_METADATA_KEY),
                text, createdAt, number(image, EXPIRES_AT_METADATA_KEY)));
    }

    private static String string(JsonNode image, String name) {
        var node = image.path(name).path("S");
        return node.isTextual() ? node.asText() : null;
    }

    private static Long number(JsonNode image, String name) {
        var node = image.path(name).path("N");
        try {
            return node.isTextual() ? Long.valueOf(node.asText()) : null;
        } catch (NumberFormatException e) {
            logger.warn("Attribute {} is not an integral number: {}", name, node.asText());
            return null;
        }
    }
}
