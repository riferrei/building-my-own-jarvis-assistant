package com.riferrei.myjarvis.services;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.injector.ContentInjector;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.router.DefaultQueryRouter;
import dev.langchain4j.rag.query.router.QueryRouter;
import dev.langchain4j.rag.query.transformer.CompressingQueryTransformer;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import com.riferrei.myjarvis.extensions.RelevanceContentAggregator;
import com.riferrei.myjarvis.extensions.SessionMemoryChat;
import com.riferrei.myjarvis.extensions.SessionMemoryStore;
import com.riferrei.myjarvis.helpers.OwnerId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.riferrei.myjarvis.helpers.Constants.*;

public class ChatAssistantService {

    private static final Logger logger = LoggerFactory.getLogger(ChatAssistantService.class);

    private final List<Object> tools;
    private final ChatModel chatModel;
    private final ScoringModel scoringModel;
    private final DynamoDbClient dynamoDbClient;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> knowledgeBaseStore;
    private final EmbeddingStore<TextSegment> userMemoryStore;

    public ChatAssistantService(ChatModel chatModel,
                                ScoringModel scoringModel,
                                DynamoDbClient dynamoDbClient,
                                EmbeddingModel embeddingModel,
                                EmbeddingStore<TextSegment> knowledgeBaseStore,
                                EmbeddingStore<TextSegment> userMemoryStore,
                                List<Object> tools) {
        this.chatModel = chatModel;
        this.scoringModel = scoringModel;
        this.dynamoDbClient = dynamoDbClient;
        this.embeddingModel = embeddingModel;
        this.knowledgeBaseStore = knowledgeBaseStore;
        this.userMemoryStore = userMemoryStore;
        this.tools = tools;
    }

    public String processQueryWithoutContext(String systemPrompt,
                                             String userId,
                                             String timeZone,
                                             String query) {
        logger.debug("Processing query without context {}", query);

        BasicChatAssistant basicChatAssistant =
                AiServices.builder(BasicChatAssistant.class)
                        .chatModel(chatModel)
                        .tools(tools)
                        .build();

        return basicChatAssistant.chat(systemPrompt, query, toolParameters(userId, timeZone));
    }

    public String processQueryWithContext(String systemPrompt,
                                          String userId,
                                          String userName,
                                          String timeZone,
                                          String query) {
        logger.debug("Processing query with context for user: {}", userId);
        long start = System.nanoTime();

        RetrievalAugmentor retrievalAugmentor = createRetrievalAugmentor(userId);

        ContextualChatAssistant contextualChatAssistant =
                AiServices.builder(ContextualChatAssistant.class)
                        .chatModel(chatModel)
                        .chatMemory(getChatMemory(userId))
                        .retrievalAugmentor(retrievalAugmentor)
                        .storeRetrievedContentInChatMemory(false)
                        .tools(tools)
                        .build();

        String answer = contextualChatAssistant.chat(systemPrompt, userId, userName, query, toolParameters(userId, timeZone));
        logger.info("Processed query with context in {} ms", elapsedMillis(start));
        return answer;
    }

    private InvocationParameters toolParameters(String userId, String timeZone) {
        var invocationParameters = new InvocationParameters();
        invocationParameters.put(USER_ID_PARAM, userId);
        invocationParameters.put(TIME_ZONE_PARAM, timeZone);
        return invocationParameters;
    }

    private RetrievalAugmentor createRetrievalAugmentor(String userId) {
        // Compress the user's query and the preceding conversation into a single query.
        // This should significantly improve the quality of the retrieval process.
        QueryTransformer queryTransformer = timed(new CompressingQueryTransformer(chatModel));

        // Source of data for retrieval. The question will be asked against the user
        // memories and the general knowledge base. The contentAggregator will be
        // responsible for scoring and aggregating the relevant content.
        QueryRouter queryRouter = new DefaultQueryRouter(
                timed("user memories", unexpired(getUserMemories(userId))),
                timed("knowledge base", getKnowledgeBase())
        );

        // Creates the precise context injection prompt the LLM will use
        // to resonate over and produce the appropriate answer. The LLM
        // will be instructed about this structure via the system prompt.
        ContentInjector contentInjector = DefaultContentInjector.builder()
                .promptTemplate(PromptTemplate.from(
                        "{{userMessage}}\n\n[Context]\n{{contents}}"
                ))
                .build();

        // Once the contents are retrieved, we need to aggregate them into
        // a content list that is coherent and relevant to the user's query.
        ContentAggregator contentAggregator = RelevanceContentAggregator.builder()
                .scoringModel(scoringModel)
                .minScore(0.5)
                .build();

        return DefaultRetrievalAugmentor.builder()
                .queryTransformer(queryTransformer)
                .queryRouter(queryRouter)
                .contentInjector(contentInjector)
                .contentAggregator(contentAggregator)
                .build();
    }

    private ChatMemory getChatMemory(String userId) {
        ChatMemoryStore sessionMemoryStore = SessionMemoryStore.builder()
                .dynamoDbClient(dynamoDbClient)
                .tableName(DYNAMODB_SESSION_MEMORY_TABLE_NAME)
                .ttlMinutes(Integer.parseInt(SESSION_MEMORY_TTL_MINUTES))
                .storeAiMessages(true)
                .build();

        return SessionMemoryChat.builder()
                .id(userId)
                .chatMemoryStore(sessionMemoryStore)
                .maxMessages(Integer.parseInt(SESSION_MEMORY_MAX_MESSAGES))
                .build();
    }

    private ContentRetriever getUserMemories(String userId) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(userMemoryStore)
                .embeddingModel(embeddingModel)
                .maxResults(Integer.parseInt(USER_MEMORIES_SEARCH_LIMIT))
                .filter(MetadataFilterBuilder.metadataKey(OWNER_ID_METADATA_KEY)
                        .isEqualTo(OwnerId.sanitize(userId)))
                .build();
    }

    private ContentRetriever getKnowledgeBase() {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(knowledgeBaseStore)
                .embeddingModel(embeddingModel)
                .maxResults(Integer.parseInt(KNOWLEDGE_BASE_SEARCH_LIMIT))
                .build();
    }

    private static QueryTransformer timed(QueryTransformer queryTransformer) {
        return query -> {
            long start = System.nanoTime();
            var queries = queryTransformer.transform(query);
            logger.info("Transformed query in {} ms", elapsedMillis(start));
            return queries;
        };
    }

    private static ContentRetriever timed(String source, ContentRetriever contentRetriever) {
        return query -> {
            long start = System.nanoTime();
            var contents = contentRetriever.retrieve(query);
            logger.info("Retrieved {} contents from {} in {} ms", contents.size(), source, elapsedMillis(start));
            return contents;
        };
    }

    private static ContentRetriever unexpired(ContentRetriever contentRetriever) {
        return query -> {
            long now = Instant.now().getEpochSecond();
            var contents = contentRetriever.retrieve(query);
            var unexpiredContents = contents.stream()
                    .filter(content -> {
                        Long expiresAt = content.textSegment().metadata().getLong(EXPIRES_AT_METADATA_KEY);
                        return expiresAt == null || expiresAt > now;
                    })
                    .toList();
            if (unexpiredContents.size() < contents.size()) {
                logger.info("Dropped {} expired contents from user memories",
                        contents.size() - unexpiredContents.size());
            }
            return unexpiredContents;
        };
    }

    private static long elapsedMillis(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

}
