package com.riferrei.myjarvis.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import com.riferrei.myjarvis.helpers.OwnerId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static com.riferrei.myjarvis.helpers.Constants.OWNER_ID_METADATA_KEY;

public class UserMemoryTool {

    private static final Logger logger = LoggerFactory.getLogger(UserMemoryTool.class);

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> userMemoryStore;

    public UserMemoryTool(EmbeddingModel embeddingModel,
                          EmbeddingStore<TextSegment> userMemoryStore) {
        this.embeddingModel = embeddingModel;
        this.userMemoryStore = userMemoryStore;
    }

    @Tool("Creates a new memory for the user")
    public boolean createUserMemory(@P("the session ID from context") String sessionId,
                                    @P("the user ID from context") String userId,
                                    @P("the timezone from context") String timezone,
                                    @P("the memory to be stored") String memory) {
        logger.info("Creating a new memory for the user: sessionId={}, userId={}, timezone={}, memory={}",
                sessionId, userId, timezone, memory);

        try {
            var textSegment = TextSegment.from(memory, Metadata.from(Map.of(
                    OWNER_ID_METADATA_KEY, OwnerId.sanitize(userId)
            )));

            var embedding = embeddingModel.embed(textSegment).content();
            userMemoryStore.add(embedding, textSegment);
            return true;
        } catch (Exception ex) {
            logger.error("Error saving user memory for user: {}", userId, ex);
            return false;
        }
    }

}
