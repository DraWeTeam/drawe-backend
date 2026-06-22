package com.drawe.backend.domain.llm.output;

import com.drawe.backend.domain.llm.contract.ReferenceImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 결정론적 참조 무결성 검사(설계 §5, ADR §6.3) — <b>재호출 없음</b>.
 *
 * <p>유효 인덱스 집합은 {@code {1 .. refs.size()}}. 이 밖의 인용은 환각이다. 본문 {@code [N]} 토큰과 {@code citations} 슬롯
 * 양쪽에서 환각만 제거하고 응답은 통과시킨다(문장 전체 삭제 아님). refs 가 비어있으면 모든 인용이 환각이므로 전부 제거한다.
 *
 * <p>본문 정정은 환각 토큰 {@code [N]} 자체만 지우고, 토큰 앞에 붙어 흐름을 깨는 공백을 정리한다(§5.1-4). 유효한 {@code [N]} 은 그대로 둔다 —
 * 사용자가 보는 번호와 references 표시 순서가 매칭되어야 하므로.
 */
@Slf4j
@Component
public class OutputIntegrityChecker {

  /** 본문 인용 토큰 {@code [N]} — N 은 1자리 이상 정수. */
  private static final Pattern CITATION_TOKEN = Pattern.compile("\\[(\\d+)\\]");

  /**
   * 환각 인용을 제거한 정정본을 만든다.
   *
   * @param raw 파싱된 원본 출력({@link OutputParser#parse} 결과).
   * @param refs 검색이 채운 references(B). null 이면 빈 리스트로 취급(전부 환각).
   * @return 정정된 출력 + 위반 카운트.
   */
  public IntegrityResult check(ComposedOutput raw, List<ReferenceImage> refs) {
    int max = refs == null ? 0 : refs.size();

    // 1. citations 슬롯 정정 — 유효 범위(1..max) 안만 남기고, 중복 제거·오름차순(표시 순서와 매칭).
    Set<Integer> validCitations = new TreeSet<>();
    int hallucinatedCitations = 0;
    for (Integer c : raw.citations()) {
      if (c != null && c >= 1 && c <= max) {
        validCitations.add(c);
      } else {
        hallucinatedCitations++;
      }
    }

    // 2. 본문 [N] 정정 — 유효 범위 밖 토큰만 제거. 유효 토큰은 보존(번호↔references 표시 순서 매칭).
    StringBuilder sb = new StringBuilder();
    Matcher m = CITATION_TOKEN.matcher(raw.message());
    int hallucinatedBodyTokens = 0;
    int last = 0;
    while (m.find()) {
      int n = Integer.parseInt(m.group(1));
      sb.append(raw.message(), last, m.start()); // 토큰 직전까지 복사
      if (n >= 1 && n <= max) {
        sb.append(m.group()); // 유효 — 토큰 그대로
      } else {
        hallucinatedBodyTokens++; // 환각 — 토큰을 건너뛴다(미복사)
      }
      last = m.end();
    }
    sb.append(raw.message(), last, raw.message().length());

    // 토큰 삭제로 생긴 흔적 정리(흐름 보존): 공백 직전·문장부호 직전의 잉여 공백, 토큰 자리의 이중 공백을 한 칸으로.
    String correctedMessage =
        hallucinatedBodyTokens > 0 ? tidyAfterRemoval(sb.toString()) : sb.toString();

    if (hallucinatedCitations > 0 || hallucinatedBodyTokens > 0) {
      log.info(
          "환각 인용 제거: refs={}, citations밖={}, 본문토큰밖={}",
          max,
          hallucinatedCitations,
          hallucinatedBodyTokens);
    }

    ComposedOutput corrected =
        new ComposedOutput(correctedMessage, new ArrayList<>(validCitations), raw.offerGenerate());
    boolean noRefs = max == 0;
    return new IntegrityResult(corrected, hallucinatedCitations, hallucinatedBodyTokens, noRefs);
  }

  /**
   * 환각 토큰 삭제 후 흔적 정리(결정론적). 줄바꿈은 보존하고 가로 공백만 손본다: 문장부호 직전의 잉여 공백 제거({@code "예시 ."}→{@code "예시."}),
   * 가로 공백 run 을 한 칸으로, 각 줄 끝 공백 제거. 토큰이 단어 사이에 있었던 경우({@code "기법[4]을"}→{@code "기법을"})는 공백이 애초에 없으므로
   * 영향 없음.
   */
  private static String tidyAfterRemoval(String s) {
    String r = s.replaceAll("[ \\t]+([,.!?;:)\\]」』”])", "$1"); // 부호 직전 공백
    r = r.replaceAll("[ \\t]{2,}", " "); // 가로 공백 run → 1
    r = r.replaceAll("[ \\t]+(\\r?\\n)", "$1"); // 줄 끝 공백
    return r.strip();
  }
}
