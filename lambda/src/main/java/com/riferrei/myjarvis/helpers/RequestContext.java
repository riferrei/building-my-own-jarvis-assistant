package com.riferrei.myjarvis.helpers;

public record RequestContext(
        String sessionId,
        String userId,
        String userName,
        String timezone) {}
