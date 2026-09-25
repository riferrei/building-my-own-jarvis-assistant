package com.riferrei.myjarvis.services;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.aggregator.ReRankingContentAggregator;
import dev.langchain4j.rag.content.injector.ContentInjector;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.router.LanguageModelQueryRouter;
import dev.langchain4j.rag.query.transformer.CompressingQueryTransformer;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import com.riferrei.myjarvis.extensions.WorkingMemoryChat;
import com.riferrei.myjarvis.extensions.WorkingMemoryStore;
import com.riferrei.myjarvis.helpers.OwnerId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static com.riferrei.myjarvis.helpers.Constants.*;

public class ChatAssistantService {

    private static final Logger logger = LoggerFactory.getLogger(ChatAssistantService.class);

    private final List<Object> tools;
    private final ChatModel chatModel;
    private final ScoringModel scoringModel;
    private final DynamoDbClient dynamoDbClient;
    private final LangCacheService langCacheService;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> knowledgeBaseStore;
    private final EmbeddingStore<TextSegment> userMemoryStore;

    public ChatAssistantService(ChatModel chatModel,
                                ScoringModel scoringModel,
                                DynamoDbClient dynamoDbClient,
                                LangCacheService langCacheService,
                                EmbeddingModel embeddingModel,
                                EmbeddingStore<TextSegment> knowledgeBaseStore,
                                EmbeddingStore<TextSegment> userMemoryStore,
                                List<Object> tools) {
        this.chatModel = chatModel;
        this.scoringModel = scoringModel;
        this.dynamoDbClient = dynamoDbClient;
        this.langCacheService = langCacheService;
        this.embeddingModel = embeddingModel;
        this.knowledgeBaseStore = knowledgeBaseStore;
        this.userMemoryStore = userMemoryStore;
        this.tools = tools;
    }

    public String processQueryWithoutContext(String systemPrompt, String query) {
        logger.debug("Processing query without context {}", query);

        BasicChatAssistant basicChatAssistant =
                AiServices.builder(BasicChatAssistant.class)
                        .chatModel(chatModel)
                        .tools(tools)
                        .build();

        return basicChatAssistant.chat(systemPrompt, query);
    }

    public String processQueryWithContext(String systemPrompt,
                                          String userId,
                                          String userName,
                                          String query) {
        logger.debug("Processing query with context for user: {}", userId);

        return langCacheService.searchForResponse(userId, query)
                .orElseGet(() -> {
                    RetrievalAugmentor augmentor = createRetrievalAugmentor(userId);

                    ContextualChatAssistant contextualChatAssistant =
                            AiServices.builder(ContextualChatAssistant.class)
                                .chatModel(chatModel)
                                .chatMemory(getChatMemory(userId))
                                .retrievalAugmentor(augmentor)
                                .tools(tools)
                                .build();

                    String response = contextualChatAssistant.chat(systemPrompt, userId, userName, query);
                    langCacheService.addNewResponse(userId, query, response);
                    return response;
                });
    }

    private RetrievalAugmentor createRetrievalAugmentor(String userId) {
        Map<ContentRetriever, String> retrievers = Map.of(
                getLongTermMemories(userId), "User specific memories like preferences, events, and interactions",
                getGeneralKnowledgeBase(), "General knowledge base (not really user related) with facts and data"
        );

        // Compress the user's query and the preceding conversation into a single query.
        // This should significantly improve the quality of the retrieval process.
        QueryTransformer queryTransformer = new CompressingQueryTransformer(chatModel);

        // This router make sure to only query the retrievers that are relevant
        // to the user query. This is more efficient in terms of context size
        LanguageModelQueryRouter router = LanguageModelQueryRouter.builder()
                .chatModel(chatModel)
                .retrieverToDescription(retrievers)
                .fallbackStrategy(LanguageModelQueryRouter.FallbackStrategy.ROUTE_TO_ALL)
                .build();

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
        ContentAggregator contentAggregator = ReRankingContentAggregator.builder()
                .scoringModel(scoringModel)
                .minScore(0.8)
                .build();

        return DefaultRetrievalAugmentor.builder()
                .queryRouter(router)
                .queryTransformer(queryTransformer)
                .contentInjector(contentInjector)
                .contentAggregator(contentAggregator)
                .build();
    }

    private ChatMemory getChatMemory(String userId) {
        ChatMemoryStore chatMemoryStore = WorkingMemoryStore.builder()
                .dynamoDbClient(dynamoDbClient)
                .tableName(DYNAMODB_SESSION_MEMORY_TABLE_NAME)
                .ttlMinutes(Integer.parseInt(SESSION_MEMORY_TTL_MINUTES))
                .maxContextWindow(Integer.parseInt(OPENAI_CHAT_MAX_TOKENS))
                .build();

        return WorkingMemoryChat.builder()
                .id(userId)
                .chatMemoryStore(chatMemoryStore)
                .build();
    }

    private ContentRetriever getLongTermMemories(String userId) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(userMemoryStore)
                .embeddingModel(embeddingModel)
                .maxResults(Integer.parseInt(USER_MEMORIES_SEARCH_LIMIT))
                .filter(MetadataFilterBuilder.metadataKey(OWNER_ID_METADATA_KEY)
                        .isEqualTo(OwnerId.sanitize(userId)))
                .build();
    }

    private ContentRetriever getGeneralKnowledgeBase() {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(knowledgeBaseStore)
                .embeddingModel(embeddingModel)
                .maxResults(Integer.parseInt(KNOWLEDGE_BASE_SEARCH_LIMIT))
                .build();
    }

}
