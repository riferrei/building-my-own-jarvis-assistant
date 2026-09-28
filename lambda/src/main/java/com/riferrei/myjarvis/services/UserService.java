package com.riferrei.myjarvis.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private static final String USER_ID_ATTRIBUTE = "userId";
    private static final String USER_NAME_ATTRIBUTE = "userName";

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    private UserService(DynamoDbClient dynamoDbClient, String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    public Optional<String> getUserName(String userId) {
        if (userId == null || userId.isBlank()) {
            logger.warn("Invalid userId provided");
            return Optional.empty();
        }

        try {
            var item = dynamoDbClient.getItem(builder -> builder
                    .tableName(tableName)
                    .key(Map.of(USER_ID_ATTRIBUTE, AttributeValue.fromS(userId)))
            ).item();

            if (item == null || item.isEmpty()) {
                return Optional.empty();
            }

            return Optional.ofNullable(item.get(USER_NAME_ATTRIBUTE))
                    .map(AttributeValue::s)
                    .filter(name -> !name.isBlank());
        } catch (Exception ex) {
            logger.error("Error fetching user record for: {}", userId, ex);
            return Optional.empty();
        }
    }

    public boolean createUser(String userId, String userName) {
        if (!validateUserInput(userId, userName)) {
            logger.warn("Invalid user input: userId={}, userName={}", userId, userName);
            return false;
        }

        var sanitizedUserName = userName.replaceAll("[\\p{Cntrl}]", "");

        try {
            dynamoDbClient.putItem(builder -> builder
                    .tableName(tableName)
                    .item(Map.of(
                            USER_ID_ATTRIBUTE, AttributeValue.fromS(userId),
                            USER_NAME_ATTRIBUTE, AttributeValue.fromS(sanitizedUserName)
                    ))
            );
            return true;
        } catch (Exception ex) {
            logger.error("Error saving user record for: {}", userId, ex);
            return false;
        }
    }

    private boolean validateUserInput(String userId, String userName) {
        return userId != null && !userId.isBlank()
                && userName != null && !userName.isBlank()
                && userId.length() <= 255
                && userName.length() <= 255;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private DynamoDbClient dynamoDbClient;
        private String tableName;

        public Builder dynamoDbClient(DynamoDbClient value) {
            this.dynamoDbClient = value;
            return this;
        }

        public Builder tableName(String value) {
            this.tableName = value;
            return this;
        }

        public UserService build() {
            Objects.requireNonNull(dynamoDbClient, "dynamoDbClient is required");
            Objects.requireNonNull(tableName, "tableName is required");
            return new UserService(dynamoDbClient, tableName);
        }
    }
}
