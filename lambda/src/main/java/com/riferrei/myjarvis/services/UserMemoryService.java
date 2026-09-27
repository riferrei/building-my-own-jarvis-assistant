package com.riferrei.myjarvis.services;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import com.riferrei.myjarvis.helpers.OwnerId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.riferrei.myjarvis.helpers.Constants.EXPIRES_AT_METADATA_KEY;
import static com.riferrei.myjarvis.helpers.Constants.OWNER_ID_METADATA_KEY;

public class UserMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(UserMemoryService.class);

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> userMemoryStore;

    public UserMemoryService(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> userMemoryStore) {
        this.embeddingModel = embeddingModel;
        this.userMemoryStore = userMemoryStore;
    }

    public boolean saveMemory(String userId, String memory, Optional<LocalDateTime> eventTime, String timeZone) {
        var expiresAt = eventTime.map(time -> expiresAt(time, timeZone));
        logger.info("Creating a new memory for the user: userId={}, memory={}, expiresAt={}",
                userId, memory, expiresAt.map(String::valueOf).orElse("never"));

        long start = System.nanoTime();
        try {
            var metadata = new HashMap<String, Object>();
            metadata.put(OWNER_ID_METADATA_KEY, OwnerId.sanitize(userId));
            expiresAt.ifPresent(epochSeconds -> metadata.put(EXPIRES_AT_METADATA_KEY, epochSeconds));

            var textSegment = TextSegment.from(memory, Metadata.from(metadata));
            var embedding = embeddingModel.embed(textSegment).content();
            userMemoryStore.add(embedding, textSegment);
            logger.info("Created a new memory for the user in {} ms",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            return true;
        } catch (Exception ex) {
            logger.error("Error saving user memory for user: {}", userId, ex);
            return false;
        }
    }

    private long expiresAt(LocalDateTime eventTime, String timeZone) {
        return eventTime.atZone(ZoneId.of(timeZone)).toEpochSecond();
    }

}
