package com.riferrei.myjarvis.helpers;

import java.util.Set;

public enum Constants {
    INSTANCE;
    public static final String SKILL_NAME = "My Jarvis";

    public static final String USER_INTRO_INTENT = "UserIntroIntent";
    public static final String REMEMBER_INTENT = "RememberIntent";
    public static final String CONVERSATION_INTENT = "ConversationIntent";
    public static final String KNOWLEDGE_BASE_INTENT = "KnowledgeBaseIntent";

    public static final String AMAZON_YES_INTENT = "AMAZON.YesIntent";
    public static final String AMAZON_NO_INTENT = "AMAZON.NoIntent";
    public static final String AMAZON_HELP_INTENT = "AMAZON.HelpIntent";
    public static final String AMAZON_STOP_INTENT = "AMAZON.StopIntent";
    public static final String AMAZON_CANCEL_INTENT = "AMAZON.CancelIntent";
    public static final String AMAZON_FALLBACK_INTENT = "AMAZON.FallbackIntent";

    public static final String OWNER_ID_METADATA_KEY = "ownerId";
    public static final String EXPIRES_AT_METADATA_KEY = "expiresAt";
    public static final String SUBJECT_METADATA_KEY = "subject";
    public static final String ATTRIBUTE_METADATA_KEY = "attribute";
    public static final String VALUE_METADATA_KEY = "value";
    public static final String CREATED_AT_METADATA_KEY = "createdAt";
    public static final String UNKNOWN_SUBJECT = "unknown";
    public static final Set<String> SINGULAR_SUBJECTS = Set.of("user", "spouse");
    public static final String USER_ID_PARAM = "userId";
    public static final String USER_NAME_PARAM = "userName";
    public static final String TIME_ZONE_PARAM = "timeZone";
    public static final String MEMORY_PARAM = "memory";
    public static final String QUERY_PARAM = "query";

    public static final String BEDROCK_CHAT_MODEL_ID = System.getenv("BEDROCK_CHAT_MODEL_ID");
    public static final String BEDROCK_CHAT_MAX_TOKENS = System.getenv("BEDROCK_CHAT_MAX_TOKENS");

    public static final String BEDROCK_RERANK_MODEL_ID =
            (System.getenv("BEDROCK_RERANK_MODEL_ID") == null ||
                    System.getenv("BEDROCK_RERANK_MODEL_ID").isEmpty())
                    ? "cohere.rerank-v3-5:0" : System.getenv("BEDROCK_RERANK_MODEL_ID");

    public static final String BEDROCK_RERANK_REGION =
            (System.getenv("BEDROCK_RERANK_REGION") == null ||
                    System.getenv("BEDROCK_RERANK_REGION").isEmpty())
                    ? System.getenv("AWS_REGION") : System.getenv("BEDROCK_RERANK_REGION");

    public static final String KNOWLEDGE_BASE_BUCKET_NAME = System.getenv("KNOWLEDGE_BASE_BUCKET_NAME");

    public static final String S3_VECTORS_BUCKET_NAME = System.getenv("S3_VECTORS_BUCKET_NAME");
    public static final String S3_VECTORS_INDEX_NAME = System.getenv("S3_VECTORS_INDEX_NAME");

    public static final String DYNAMODB_USERS_TABLE_NAME = System.getenv("DYNAMODB_USERS_TABLE_NAME");
    public static final String DYNAMODB_SESSION_MEMORY_TABLE_NAME = System.getenv("DYNAMODB_SESSION_MEMORY_TABLE_NAME");

    public static final String SESSION_MEMORY_TTL_MINUTES =
            (System.getenv("SESSION_MEMORY_TTL_MINUTES") == null ||
                    System.getenv("SESSION_MEMORY_TTL_MINUTES").isEmpty())
                    ? String.valueOf(5) : System.getenv("SESSION_MEMORY_TTL_MINUTES");

    public static final String SESSION_MEMORY_MAX_MESSAGES =
            (System.getenv("SESSION_MEMORY_MAX_MESSAGES") == null ||
                    System.getenv("SESSION_MEMORY_MAX_MESSAGES").isEmpty())
                    ? String.valueOf(20) : System.getenv("SESSION_MEMORY_MAX_MESSAGES");

    public static final String DYNAMODB_USER_MEMORY_TABLE_NAME = System.getenv("DYNAMODB_USER_MEMORY_TABLE_NAME");
    public static final String DYNAMODB_USER_MEMORY_INDEX_NAME =
            (System.getenv("DYNAMODB_USER_MEMORY_INDEX_NAME") == null ||
                    System.getenv("DYNAMODB_USER_MEMORY_INDEX_NAME").isEmpty())
                    ? "user-memories" : System.getenv("DYNAMODB_USER_MEMORY_INDEX_NAME");

    public static final String EMBEDDING_MODEL_NAME =
            (System.getenv("EMBEDDING_MODEL_NAME") == null ||
                    System.getenv("EMBEDDING_MODEL_NAME").isEmpty())
                    ? "amazon.titan-embed-text-v2:0" : System.getenv("EMBEDDING_MODEL_NAME");

    public static final String EMBEDDING_DIMENSIONS =
            (System.getenv("EMBEDDING_DIMENSIONS") == null ||
                    System.getenv("EMBEDDING_DIMENSIONS").isEmpty())
                    ? String.valueOf(1024) : System.getenv("EMBEDDING_DIMENSIONS");

    public static final String MAX_SEGMENT_SIZE_IN_CHARS =
            (System.getenv("MAX_SEGMENT_SIZE_IN_CHARS") == null ||
                    System.getenv("MAX_SEGMENT_SIZE_IN_CHARS").isEmpty())
                    ? String.valueOf(1000) : System.getenv("MAX_SEGMENT_SIZE_IN_CHARS");

    public static final String MAX_SEGMENT_OVERLAP_IN_CHARS =
            (System.getenv("MAX_SEGMENT_OVERLAP_IN_CHARS") == null ||
                    System.getenv("MAX_SEGMENT_OVERLAP_IN_CHARS").isEmpty())
                    ? String.valueOf(200) : System.getenv("MAX_SEGMENT_OVERLAP_IN_CHARS");

    public static final String USER_MEMORIES_SEARCH_LIMIT =
            (System.getenv("USER_MEMORIES_SEARCH_LIMIT") == null ||
                    System.getenv("USER_MEMORIES_SEARCH_LIMIT").isEmpty())
                    ? String.valueOf(10) : System.getenv("USER_MEMORIES_SEARCH_LIMIT");

    public static final int CONSOLIDATION_SEARCH_LIMIT = 5;

    public static final String KNOWLEDGE_BASE_SEARCH_LIMIT =
            (System.getenv("KNOWLEDGE_BASE_SEARCH_LIMIT") == null ||
                    System.getenv("KNOWLEDGE_BASE_SEARCH_LIMIT").isEmpty())
                    ? String.valueOf(5) : System.getenv("KNOWLEDGE_BASE_SEARCH_LIMIT");
}
