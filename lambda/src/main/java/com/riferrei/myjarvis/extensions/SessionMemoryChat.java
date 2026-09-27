package com.riferrei.myjarvis.extensions;

import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class SessionMemoryChat implements ChatMemory {

    private static final Logger logger = LoggerFactory.getLogger(SessionMemoryChat.class);

    private final String id;
    private final ChatMemoryStore chatMemoryStore;
    private final int maxMessages;
    private final List<ChatMessage> messages;

    public SessionMemoryChat(String id,
                             ChatMemoryStore chatMemoryStore,
                             int maxMessages) {
        this.id = id;
        this.chatMemoryStore = chatMemoryStore;
        this.maxMessages = maxMessages;

        // Load existing messages
        this.messages = new ArrayList<>(chatMemoryStore.getMessages(id));
        logger.debug("Initialized SessionChatMemory for session {} with {} messages",
                id, this.messages.size());
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public void add(ChatMessage message) {
        messages.add(message);
        chatMemoryStore.updateMessages(id, messages);
    }

    @Override
    public List<ChatMessage> messages() {
        var conversation = messages.stream()
                .filter(message -> !(message instanceof SystemMessage))
                .toList();

        var window = new ArrayList<>(conversation.subList(
                Math.max(0, conversation.size() - maxMessages), conversation.size()));

        while (!window.isEmpty() && !(window.getFirst() instanceof UserMessage)) {
            window.removeFirst();
        }

        SystemMessage.findFirst(messages).ifPresent(window::addFirst);
        return window;
    }

    @Override
    public void clear() {
        messages.clear();
        chatMemoryStore.deleteMessages(id);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private ChatMemoryStore chatMemoryStore;
        private Integer maxMessages;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder chatMemoryStore(ChatMemoryStore chatMemoryStore) {
            this.chatMemoryStore = chatMemoryStore;
            return this;
        }

        public Builder maxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
            return this;
        }

        public SessionMemoryChat build() {
            Objects.requireNonNull(maxMessages, "maxMessages is required");
            return new SessionMemoryChat(id, chatMemoryStore, maxMessages);
        }
    }
}
