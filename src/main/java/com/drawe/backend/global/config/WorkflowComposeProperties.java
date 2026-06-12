package com.drawe.backend.global.config;

import com.drawe.backend.domain.llm.contract.IntentCode;
import java.util.EnumSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * COMPOSE 메인경로(shadow→live) 점진 전환 플래그 (S2' 트랙 A ⑤, 설계 §3.3).
 *
 * <p>레거시 {@code ChatLlmService.chat()} 직접 합성 경로를 {@code WorkflowService} 전체 워크플로로 전환하는
 * 토글이다. <b>기본은 전부 off</b> — 아무 의도도 live 가 아니면 기존(레거시) 경로 그대로다. 운영자가 의도별로
 * 켜야만 그 의도가 live 경로를 탄다(점진 전환·롤백 가능).
 *
 * <p>설계 §3.3 안전장치 그대로: NEW_SEARCH·KEEP·SKIP·001~004(전부 COMPOSE 종착)부터 의도별로 켠다.
 * 한 의도라도 shadow outcome 이 {@code match} 가 아니면 그 의도는 live 에서 빼고 레거시로 둔 채 원인 분석한다.
 *
 * <h3>전환 시 사라지는 베타 안전장치(운영자 인지 필수)</h3>
 * live 경로(WorkflowService)는 레거시 {@code handleSearchDecision} 의 점수 가드(avg&lt;0.2 || max&lt;0.21 →
 * 무관 결과 차단)와 SEARCH_EXECUTED/SEARCH_BLOCKED analytics 를 <b>아직 재현하지 않는다</b>. 또한 검색
 * 키워드가 Grok→Komoran(EXTRACT_KEYWORDS) 로 바뀐다. 그래서 켜는 순간 {@code chat()} 이 한 줄 WARN 으로
 * 이 손실을 남긴다(silent 전환 금지). 이 갭은 ⑦(메트릭·점수가드 이관)에서 닫는다.
 *
 * <p>예: {@code application.properties}
 * <pre>{@code
 * workflow.compose.live-intents=NEW_SEARCH
 * }</pre>
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "workflow.compose")
public class WorkflowComposeProperties {

  /**
   * live 경로(전체 워크플로)로 전환할 의도 집합. 비어 있으면(기본) 모든 의도가 레거시 경로다. 운영자가 의도별로
   * 추가해 점진 전환한다. {@code IntentCode} enum 이름으로 바인딩된다(예: {@code NEW_SEARCH}).
   */
  private Set<IntentCode> liveIntents = EnumSet.noneOf(IntentCode.class);

  /** 해당 의도가 live(전체 워크플로) 경로인지. */
  public boolean isLive(IntentCode code) {
    return code != null && liveIntents.contains(code);
  }
}
