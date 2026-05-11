package com.drawe.backend.domain.onboarding.dto;

public record OnboardingImage(
    Long id, String url, String technique, String subject, String mood, String label) {}
