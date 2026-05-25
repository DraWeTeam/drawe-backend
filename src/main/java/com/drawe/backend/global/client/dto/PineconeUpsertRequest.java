package com.drawe.backend.global.client.dto;

import java.util.List;

public record PineconeUpsertRequest(List<PineconeVector> vectors) {}
