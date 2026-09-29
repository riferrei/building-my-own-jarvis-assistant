package com.riferrei.myjarvis;

import com.amazon.ask.Skill;
import com.amazon.ask.SkillStreamHandler;
import com.amazon.ask.Skills;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.community.store.embedding.dynamodb.DynamoDbEmbeddingStore;
import dev.langchain4j.community.store.embedding.s3.S3VectorsEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.bedrock.BedrockChatRequestParameters;
import dev.langchain4j.model.bedrock.BedrockTitanEmbeddingModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.VectorDistanceFunction;
import software.amazon.awssdk.services.s3vectors.model.DistanceMetric;
import com.riferrei.myjarvis.extensions.BedrockScoringModel;
import com.riferrei.myjarvis.helpers.LatencyListener;
import com.riferrei.myjarvis.handlers.*;
import com.riferrei.myjarvis.helpers.UserDoesNotExistExceptionHandler;
import com.riferrei.myjarvis.helpers.UserValidationInterceptor;
import com.riferrei.myjarvis.services.*;

import java.util.List;
import java.util.Map;

import static com.riferrei.myjarvis.helpers.Constants.*;

public class MyJarvisStreamHandler extends SkillStreamHandler {

    private static final DocumentParser documentParser = new ApachePdfBoxDocumentParser(true);
    private static final DocumentSplitter documentSplitter = new DocumentByParagraphSplitter(
            Integer.parseInt(MAX_SEGMENT_SIZE_IN_CHARS),
            Integer.parseInt(MAX_SEGMENT_OVERLAP_IN_CHARS)
    );

    private static final ChatModel chatModel = BedrockChatModel.builder()
            .region(Region.of(System.getenv("AWS_REGION")))
            .modelId(BEDROCK_CHAT_MODEL_ID)
            .defaultRequestParameters(BedrockChatRequestParameters.builder()
                    .maxOutputTokens(Integer.parseInt(BEDROCK_CHAT_MAX_TOKENS))
                    .additionalModelRequestField("thinking", Map.of("type", "disabled"))
                    .build())
            .listeners(new LatencyListener())
            .build();

    private static final ChatModel compressionModel = BedrockChatModel.builder()
            .region(Region.of(System.getenv("AWS_REGION")))
            .modelId(BEDROCK_COMPRESSION_MODEL_ID)
            .defaultRequestParameters(BedrockChatRequestParameters.builder()
                    .maxOutputTokens(Integer.parseInt(BEDROCK_CHAT_MAX_TOKENS))
                    .additionalModelRequestField("thinking", Map.of("type", "disabled"))
                    .build())
            .listeners(new LatencyListener())
            .build();

    private static final BedrockAgentRuntimeClient bedrockAgentRuntimeClient = BedrockAgentRuntimeClient.builder()
            .region(Region.of(BEDROCK_RERANK_REGION))
            .build();

    private static final ScoringModel scoringModel = BedrockScoringModel.builder()
            .bedrockAgentRuntimeClient(bedrockAgentRuntimeClient)
            .modelId(BEDROCK_RERANK_MODEL_ID)
            .build();

    private static final EmbeddingModel embeddingModel = BedrockTitanEmbeddingModel.builder()
            .region(Region.of(System.getenv("AWS_REGION")))
            .model(EMBEDDING_MODEL_NAME)
            .dimensions(Integer.parseInt(EMBEDDING_DIMENSIONS))
            .normalize(true)
            .build();

    private static final EmbeddingStore<TextSegment> knowledgeBaseStore = S3VectorsEmbeddingStore.builder()
            .region(System.getenv("AWS_REGION"))
            .vectorBucketName(S3_VECTORS_BUCKET_NAME)
            .indexName(S3_VECTORS_INDEX_NAME)
            .distanceMetric(DistanceMetric.COSINE)
            .createIndexIfNotExists(false)
            .build();

    private static final EmbeddingStore<TextSegment> userMemoryStore = DynamoDbEmbeddingStore.builder()
            .region(System.getenv("AWS_REGION"))
            .tableName(DYNAMODB_USER_MEMORY_TABLE_NAME)
            .indexName(DYNAMODB_USER_MEMORY_INDEX_NAME)
            .distanceFunction(VectorDistanceFunction.COSINE)
            .inlineFilterAttributes(List.of(OWNER_ID_METADATA_KEY, SUBJECT_METADATA_KEY))
            .createTableIfNotExists(false)
            .build();

    private static final ReminderService reminderService = new ReminderService();

    private static final DynamoDbClient dynamoDbClient = DynamoDbClient.builder()
            .region(Region.of(System.getenv("AWS_REGION")))
            .build();

    private static final UserService userService = UserService.builder()
            .dynamoDbClient(dynamoDbClient)
            .tableName(DYNAMODB_USERS_TABLE_NAME)
            .build();

    private static final UserMemoryService userMemoryService =
            new UserMemoryService(embeddingModel, userMemoryStore);

    private static final ChatAssistantService chatAssistantService =
            new ChatAssistantService(
                    chatModel, compressionModel, scoringModel, dynamoDbClient,
                    embeddingModel, knowledgeBaseStore, userMemoryStore
            );

    public MyJarvisStreamHandler() {
        super(getSkill());
    }

    private static Skill getSkill() {
        return Skills.standard()
                .addRequestInterceptor(new UserValidationInterceptor(userService))
                .addExceptionHandler(new UserDoesNotExistExceptionHandler())
                .addRequestHandlers(
                        new YesIntentHandler(reminderService),
                        new NoIntentHandler(),
                        new LaunchRequestHandler(),
                        new CancelAndStopIntentHandler(),
                        new FallbackIntentHandler(),
                        new HelpIntentHandler(),
                        new UserIntroIntentHandler(userService, chatAssistantService),
                        new RememberIntentHandler(chatAssistantService, userMemoryService),
                        new ConversationIntentHandler(chatAssistantService),
                        new KnowledgeBaseIntentHandler(documentParser, documentSplitter,
                                embeddingModel, knowledgeBaseStore)
                )
                .build();
    }
}
