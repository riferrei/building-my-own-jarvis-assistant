package com.riferrei.myjarvis.services;

import com.riferrei.myjarvis.helpers.MemoryKey;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.riferrei.myjarvis.helpers.Constants.ATTRIBUTE_METADATA_KEY;
import static com.riferrei.myjarvis.helpers.Constants.CREATED_AT_METADATA_KEY;
import static com.riferrei.myjarvis.helpers.Constants.EXPIRES_AT_METADATA_KEY;
import static com.riferrei.myjarvis.helpers.Constants.OWNER_ID_METADATA_KEY;
import static com.riferrei.myjarvis.helpers.Constants.SINGULAR_SUBJECTS;
import static com.riferrei.myjarvis.helpers.Constants.SUBJECT_METADATA_KEY;
import static com.riferrei.myjarvis.helpers.Constants.UNKNOWN_SUBJECT;
import static com.riferrei.myjarvis.helpers.Constants.VALUE_METADATA_KEY;

public class UserMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(UserMemoryService.class);

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> userMemoryStore;

    public UserMemoryService(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> userMemoryStore) {
        this.embeddingModel = embeddingModel;
        this.userMemoryStore = userMemoryStore;
    }

    public boolean saveMemory(String userId, String memory, MemoryKey memoryKey,
                              Optional<LocalDateTime> eventTime, String timeZone) {
        var subject = normalize(memoryKey.subject()).orElse(UNKNOWN_SUBJECT);
        var attribute = normalize(memoryKey.attribute()).filter(name -> isKeyable(subject));
        var value = attribute.flatMap(name -> normalize(memoryKey.value()));
        var id = memoryId(userId, subject, attribute, value);
        var expiresAt = eventTime.map(time -> expiresAt(time, timeZone));
        logger.info("Creating a new memory for the user: id={}, memory={}, expiresAt={}",
                id, memory, expiresAt.map(String::valueOf).orElse("never"));

        long start = System.nanoTime();
        try {
            var metadata = new HashMap<String, Object>();
            metadata.put(OWNER_ID_METADATA_KEY, userId);
            metadata.put(SUBJECT_METADATA_KEY, subject);
            attribute.ifPresent(name -> metadata.put(ATTRIBUTE_METADATA_KEY, name));
            value.ifPresent(text -> metadata.put(VALUE_METADATA_KEY, text));
            metadata.put(CREATED_AT_METADATA_KEY, System.currentTimeMillis());
            expiresAt.ifPresent(epochSeconds -> metadata.put(EXPIRES_AT_METADATA_KEY, epochSeconds));

            var textSegment = TextSegment.from(memory, Metadata.from(metadata));
            var embedding = embeddingModel.embed(textSegment).content();
            userMemoryStore.addAll(List.of(id), List.of(embedding), List.of(textSegment));
            logger.info("Created a new memory for the user in {} ms",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            return true;
        } catch (Exception ex) {
            logger.error("Error saving user memory for user: {}", userId, ex);
            return false;
        }
    }

    private String memoryId(String userId, String subject, Optional<String> attribute, Optional<String> value) {
        if (attribute.isEmpty()) {
            return UUID.randomUUID().toString();
        }
        return Stream.concat(Stream.of(userId, subject, attribute.get()), value.stream())
                .collect(Collectors.joining("#"));
    }

    private boolean isKeyable(String subject) {
        return subject.contains(":") || SINGULAR_SUBJECTS.contains(subject);
    }

    private Optional<String> normalize(String text) {
        return Optional.ofNullable(text)
                .map(raw -> raw.strip().toLowerCase(Locale.ROOT).replace("#", "").replaceAll("\\s+", "_"))
                .filter(key -> !key.isEmpty() && !"null".equals(key));
    }

    private long expiresAt(LocalDateTime eventTime, String timeZone) {
        return eventTime.atZone(ZoneId.of(timeZone)).toEpochSecond();
    }

}
