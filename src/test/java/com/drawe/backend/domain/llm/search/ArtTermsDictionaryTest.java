package com.drawe.backend.domain.llm.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ArtTermsDictionary 단위 테스트.
 *
 * <p>실제 CSV 파일({@code src/main/resources/art-terms-ko-en.csv})을 로드하여 핵심 시드 단어들이 매핑되는지 검증.
 */
class ArtTermsDictionaryTest {

  private ArtTermsDictionary dictionary;

  @BeforeEach
  void setUp() {
    dictionary = new ArtTermsDictionary();
    dictionary.load();
  }

  @Test
  @DisplayName("사전 로드 — 최소 한 개 이상의 엔트리가 로드된다")
  void loadsEntries() {
    assertThat(dictionary.size()).isGreaterThan(0);
  }

  @Test
  @DisplayName("기법 카테고리 — 수채화·유화·디지털 매핑")
  void techniqueLookup() {
    assertThat(dictionary.lookup("수채화")).contains("watercolor");
    assertThat(dictionary.lookup("유화")).contains("oil painting");
    assertThat(dictionary.lookup("디지털")).contains("digital art");
  }

  @Test
  @DisplayName("주제 카테고리 — 고양이·강아지·풍경 매핑")
  void subjectLookup() {
    assertThat(dictionary.lookup("고양이")).contains("cat");
    assertThat(dictionary.lookup("강아지")).contains("puppy");
    assertThat(dictionary.lookup("풍경")).contains("landscape");
  }

  @Test
  @DisplayName("개념 카테고리 — 구도·명암·비율 매핑")
  void conceptLookup() {
    assertThat(dictionary.lookup("구도")).contains("composition");
    assertThat(dictionary.lookup("명암")).contains("light and shadow");
    assertThat(dictionary.lookup("비율")).contains("proportion");
  }

  @Test
  @DisplayName("액션·포즈 동사 어간 — 달리·앉·자")
  void actionPoseLookup() {
    assertThat(dictionary.lookup("달리")).contains("running");
    assertThat(dictionary.lookup("앉")).contains("sitting");
    assertThat(dictionary.lookup("자")).contains("sleeping");
    assertThat(dictionary.lookup("웃")).contains("smiling");
  }

  @Test
  @DisplayName("사전에 없는 단어 — Optional.empty")
  void missingWordReturnsEmpty() {
    assertThat(dictionary.lookup("존재하지않는단어")).isEmpty();
    assertThat(dictionary.lookup("xyz")).isEmpty();
  }

  @Test
  @DisplayName("null·빈 문자열 — Optional.empty")
  void nullOrEmptyReturnsEmpty() {
    assertThat(dictionary.lookup(null)).isEmpty();
    assertThat(dictionary.lookup("")).isEmpty();
  }

  @Test
  @DisplayName("카테고리 조회 — 수채화 = technique")
  void categoryLookup() {
    assertThat(dictionary.getCategory("수채화")).contains("technique");
    assertThat(dictionary.getCategory("고양이")).contains("subject");
    assertThat(dictionary.getCategory("구도")).contains("concept");
    assertThat(dictionary.getCategory("달리")).contains("action");
    assertThat(dictionary.getCategory("앉")).contains("pose");
  }

  @Test
  @DisplayName("카테고리 미존재 단어 — Optional.empty")
  void missingCategoryReturnsEmpty() {
    assertThat(dictionary.getCategory("존재하지않는단어")).isEmpty();
  }

  @Test
  @DisplayName("PoC 시뮬레이션 — '수채화 풍경'에서 둘 다 매핑")
  void simulatePoC() {
    Optional<String> watercolor = dictionary.lookup("수채화");
    Optional<String> landscape = dictionary.lookup("풍경");

    assertThat(watercolor).isPresent();
    assertThat(landscape).isPresent();
    assertThat(watercolor.get()).isEqualTo("watercolor");
    assertThat(landscape.get()).isEqualTo("landscape");
  }
}
