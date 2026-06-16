package com.drawe.backend.global.config;

import com.drawe.backend.domain.llm.contract.IntentCode;
import com.drawe.backend.domain.llm.contract.IntentRouting;
import com.drawe.backend.domain.llm.contract.StepType;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
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
 * <h3>전환 시 레거시 대비 차이(운영자 인지 필수)</h3>
 * 점수 가드(avg&lt;0.2 || max&lt;0.21 무관 결과 차단)와 SEARCH_EXECUTED/SEARCH_BLOCKED·DECISION_KEEP/SKIP
 * analytics 는 live 경로도 <b>재현한다</b>(2026-06, {@code SearchExecutor}·{@code chatViaWorkflow} 의
 * emit* 헬퍼). <b>남은 의도된 차이는 검색 키워드 소스</b> — Grok(레거시)→Komoran(EXTRACT_KEYWORDS, live)로
 * 바뀌어 같은 입력에 결과가 갈릴 수 있다. 그래서 켜는 순간 {@code chatViaWorkflow} 가 한 줄 WARN 을 남긴다.
 * shadow outcome({@code drawe.workflow.shadow})이 {@code match} 인 의도부터 켜는 것을 전제로 한다.
 *
 * <p>예: {@code application.properties}
 * <pre>{@code
 * workflow.compose.live-intents=NEW_SEARCH
 * }</pre>
 */
@Slf4j
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

  /**
   * 부팅 검증(R1 방어) — live 로 켜진 의도가 <b>전부 COMPOSE 로 종착</b>하는지 확인한다.
   *
   * <p>{@code chatViaWorkflow} 는 {@code finalCtx.composedOutput()} 으로 응답을 만든다. ROUTING 시퀀스에
   * COMPOSE 가 없는 의도(예: {@code GENERATE}=[TRANSLATE, GENERATE_IMAGE])를 live 로 켜면 composedOutput 이
   * null 이라 런타임에 AI_SERVICE_ERROR(500)로 죽는다 — 게다가 골격 Executor 는 {@code outcome=success} 로
   * 집계돼 관측 신호도 없다. 그 위험한 설정을 런타임 500 대신 <b>부팅 시점에 즉시</b> 드러낸다(fail-fast).
   *
   * <p>ROUTING 에 매핑 자체가 없는 의도(011~013)도 빈 시퀀스라 composedOutput 을 못 만들므로 함께 막는다.
   */
  @PostConstruct
  void validateLiveIntents() {
    List<String> invalid = new ArrayList<>();
    for (IntentCode code : liveIntents) {
      List<StepType> steps = IntentRouting.ROUTING.get(code);
      if (steps == null || !steps.contains(StepType.COMPOSE)) {
        invalid.add(code.name() + (steps == null ? "(ROUTING 미매핑)" : steps.toString()));
      }
    }
    if (!invalid.isEmpty()) {
      throw new IllegalStateException(
          "workflow.compose.live-intents 설정 오류 — COMPOSE 로 종착하지 않는 의도는 live 로 켤 수 없다"
              + "(composedOutput 이 null 이라 런타임 500). 문제 의도: "
              + invalid
              + ". live 대상은 COMPOSE 종착 의도(NEW_SEARCH/KEEP/SKIP/COMPOSITION/LIGHTING/COLOR/TECHNIQUE/"
              + "OUT_OF_DOMAIN/SELF_CRITIQUE)로 한정할 것.");
    }
    if (!liveIntents.isEmpty()) {
      log.info("workflow.compose live 의도(부팅 검증 통과): {}", liveIntents);
    }
  }
}
