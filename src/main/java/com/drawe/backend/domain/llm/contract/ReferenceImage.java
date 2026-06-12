package com.drawe.backend.domain.llm.contract;

import java.math.BigDecimal;
import java.util.List;

/**
 * 검색으로 찾은 레퍼런스 이미지. B 가 채우고 A 가 인용 무결성 검사에 사용한다.
 *
 * <p>인용 무결성 검사는 {@link #index} 로 한다 — 사용자가 보는 번호([1][2][3])와 LLM 응답 안의 인용이 매칭되어야 함.
 * {@link #imageId} 는 DB 키, 메트릭/로깅용.
 *
 * <p>기존 {@code com.drawe.backend.domain.search.dto.ImageResult} 와 필드가 일부 겹치지만,
 * contract 패키지는 도메인 간 의존을 끊기 위해 별도 타입으로 둔다.
 * search 도메인 → contract 어댑터를 B 의 SearchExecutor 에서 변환.
 *
 * <h3>표시용 필드 (live 갭 복원)</h3>
 * {@code photographerUsername}/{@code technique}/{@code subject}/{@code mood}/{@code source} 는
 * 인용 무결성과 무관하고 오직 {@code ChatResponse.ReferenceItem} 표시(프론트 AI 배지 등)에만 쓴다.
 * 레거시 {@code ChatLlmService.convertToReferenceItems}(ImageResult 기반)가 채우던 필드를 live 경로
 * (ReferenceImage 기반)에서도 복원하기 위해 추가했다. 6-인자 생성자는 이 필드들을 {@code null} 로 채우는
 * 편의 생성자로 보존한다 — 세션 복원·테스트 등 표시 필드가 불필요한 생성 지점은 그대로 둔다.
 *
 * @param imageId              DB 이미지 ID (메트릭·로깅용).
 * @param index                사용자 표시 순서(1-based). LLM 인용 무결성 검사 키.
 * @param url                  이미지 URL.
 * @param photographer         사진작가 이름 (없으면 null).
 * @param score                검색 점수 (CLIP 유사도 등). Score Guard 판정 결과 그대로 노출.
 * @param tags                 태그 목록 (technique/subject/mood/utility/freeTags 등 합쳐서).
 * @param photographerUsername 사진작가 username (표시용, 없으면 null).
 * @param technique            기법 (표시용, 없으면 null).
 * @param subject              주제 (표시용, 없으면 null).
 * @param mood                 분위기 (표시용, 없으면 null).
 * @param source               출처 enum 이름 "UNSPLASH" | "AI" (프론트 AI 배지용, 없으면 null).
 */
public record ReferenceImage(
    Long imageId,
    int index,
    String url,
    String photographer,
    BigDecimal score,
    List<String> tags,
    String photographerUsername,
    String technique,
    String subject,
    String mood,
    String source
) {

  public ReferenceImage {
    tags = tags == null ? List.of() : List.copyOf(tags);
  }

  /**
   * 표시용 필드 없이 인용 무결성에 필요한 최소 필드만 받는 보존 생성자 (표시 필드는 모두 {@code null}).
   * 세션 복원·테스트 등 ChatResponse 표시가 불필요한 생성 지점이 기존 6-인자 시그니처를 그대로 쓰게 한다.
   */
  public ReferenceImage(
      Long imageId,
      int index,
      String url,
      String photographer,
      BigDecimal score,
      List<String> tags) {
    this(imageId, index, url, photographer, score, tags, null, null, null, null, null);
  }
}
