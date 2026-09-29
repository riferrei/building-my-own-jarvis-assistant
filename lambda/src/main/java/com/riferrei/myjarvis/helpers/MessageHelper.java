package com.riferrei.myjarvis.helpers;

import dev.langchain4j.data.message.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MessageHelper {

    private static final Logger logger = LoggerFactory.getLogger(MessageHelper.class);

    public static String messageContent(ChatMessage message) {
        return switch (message) {
            case UserMessage userMessage -> userMessage.singleText();
            case AiMessage aiMessage -> aiMessage.text();
            case SystemMessage systemMessage -> systemMessage.text();
            case ToolExecutionResultMessage toolExecutionResultMessage -> toolExecutionResultMessage.text();
            default -> {
                logger.warn("Unknown message type for content extraction: {}", message.getClass().getName());
                yield message.toString();
            }
        };
    }

}
