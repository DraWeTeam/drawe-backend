package com.drawe.backend.global.client.dto;

import java.util.List;
import java.util.Map;

/** Pinecone upsert에 보내는 단일 벡터 항목. metadata는 source/userId/prompt 등 필터·필드 노출용. */
public record PineconeVector(String id, List<Float> values, Map<String, Object> metadata) {}
