package com.drawe.backend.domain.image.event;

import com.drawe.backend.domain.Project;

/**
 * AI 이미지가 MySQL에 저장된 직후 발행되는 이벤트.
 *
 * <p>{@code ImageGenerationService.generate()} 트랜잭션이 commit 되면
 * AiImageIndexService가 AFTER_COMMIT 단계에서 받아 CLIP 임베딩 + Pinecone upsert 를 수행한다.
 *
 * <p>왜 이벤트인가: 호출 측 트랜잭션이 commit 되기 전에 @Async 비동기 인덱서가 실행되면,
 * 새 트랜잭션은 아직 commit 안 된 images 행을 못 봐서 findById 가 null 을 돌려준다
 * ("AI 이미지 적재 대상 없음" 로그가 그 증상). AFTER_COMMIT phase 보장이 필요하다.
 */
public record AiImageCreatedEvent(Long imageId, byte[] imageBytes, String mimeType, Project project) {}
