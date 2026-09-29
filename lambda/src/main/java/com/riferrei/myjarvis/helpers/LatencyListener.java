package com.riferrei.myjarvis.helpers;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LatencyListener implements ChatModelListener {

    private static final Logger logger = LoggerFactory.getLogger(LatencyListener.class);

    private static final String START_NANOS = "startNanos";

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        requestContext.attributes().put(START_NANOS, System.nanoTime());
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        var chatResponse = responseContext.chatResponse();
        var tokenUsage = chatResponse.tokenUsage();
        logger.info("Chat model call took {} ms (input tokens: {}, output tokens: {}, finish reason: {})",
                elapsedMillis(responseContext.attributes()),
                tokenUsage == null ? null : tokenUsage.inputTokenCount(),
                tokenUsage == null ? null : tokenUsage.outputTokenCount(),
                chatResponse.finishReason());
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        logger.warn("Chat model call failed after {} ms", elapsedMillis(errorContext.attributes()));
    }

    private static Long elapsedMillis(Map<Object, Object> attributes) {
        return attributes.get(START_NANOS) instanceof Long start
                ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                : null;
    }
}
