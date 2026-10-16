package com.muse.meomuneum.recommendation.service;

import java.util.HashSet;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class ImageRecommendationAdmission {
    private static final int MAX_ACTIVE = 2;
    private final Set<String> conversations = new HashSet<>();

    public synchronized boolean reserve(String conversationKey) {
        if (conversations.size() >= MAX_ACTIVE || conversations.contains(conversationKey)) {
            return false;
        }
        conversations.add(conversationKey);
        return true;
    }

    public synchronized void release(String conversationKey) {
        conversations.remove(conversationKey);
    }
}
