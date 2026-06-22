package com.drawe.backend.global.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Pinecone upsert 요청 본문.
 *
 * <p>{@code namespace} 는 적재 대상 파티션. null/미지정이면 Pinecone 의 default namespace("") 로 들어간다 — 기존
 * 일반(Unsplash/Bria) 적재는 default namespace 를 그대로 사용한다.
 *
 * <p>가이드용 이미지(FastAPI+Gemini) 벡터는 일반 검색에 섞이면 안 되므로 별도 namespace(예: {@code "guide"}) 로 분리 적재한다. 결정
 * 배경: {@code docs/decisions/S3-guide-namespace-design.md}.
 *
 * <p>{@code @JsonInclude(NON_NULL)} — namespace 가 null 이면 필드를 직렬화에서 제외해, Pinecone 이 default
 * namespace 로 처리하게 한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PineconeUpsertRequest(List<PineconeVector> vectors, String namespace) {

  /** default namespace("") 로 적재 — 기존 일반 적재 경로 호환용. */
  public PineconeUpsertRequest(List<PineconeVector> vectors) {
    this(vectors, null);
  }
}
