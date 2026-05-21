package com.drawe.backend.domain.llm.service;

import com.drawe.backend.domain.Project;
import com.drawe.backend.domain.User;
import com.drawe.backend.domain.enums.LlmProvider;
import com.drawe.backend.domain.enums.MessageRole;
import com.drawe.backend.domain.llm.dto.LlmCallContext;
import com.drawe.backend.domain.llm.dto.LlmCallResult;
import com.drawe.backend.domain.log.PromptTranslationLog;
import com.drawe.backend.domain.log.PromptTranslationLogRepository;
import com.drawe.backend.global.error.CustomException;
import com.drawe.backend.global.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 한국어 채팅 메시지를 Bria 가 잘 이해하는 영문 image-generation prompt 로 변환한다.
 *
 * <p>고정 모델 (Grok) 을 사용 — 변환 작업은 단순·짧아서 저렴한 모델로 충분, 비용·응답시간 예측성 확보. 프로젝트의 subject/technique/mood 가 있으면
 * 자동으로 함께 반영한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptTranslator {

  private static final LlmProvider TRANSLATOR_PROVIDER = LlmProvider.GROK;

  private static final String SYSTEM_INSTRUCTION =
      "You convert Korean user requests into a single English image-generation prompt for the Bria AI model.\n"
          + "Rules:\n"
          + "1. Respond with a single JSON object only: {\"prompt\": \"<english prompt>\"}\n"
          + "   No prose, no markdown, no code fences, no extra fields.\n"
          + "2. Keep the prompt under 60 words. Concrete nouns, adjectives, lighting, composition, mood.\n"
          + "3. If project context is provided (subject, technique, mood), weave it in naturally.\n"
          + "4. Do not invent unrelated elements. Stay faithful to the user request.\n"
          + "5. Prefer commercial-safe descriptors. No brand names, no real person names.";

  private static final Pattern CODE_FENCE = Pattern.compile("(?s)^```(?:json)?\\s*|\\s*```$");
  private static final Pattern LEADING_PREFIX =
      Pattern.compile(
          "(?i)^(sure[,!.]?\\s*|here(?:'s| is)?\\s+(?:the\\s+)?(?:english\\s+)?prompt[:\\-\\s]*|prompt[:\\-\\s]*|english\\s+prompt[:\\-\\s]*)");

  private final List<LlmService> llmServices;
  private final ObjectMapper objectMapper;
  private final PromptTranslationLogRepository translationLogRepository;

  public String translate(User user, String userPrompt, Project project) {
    LlmService llm = pickService();

    StringBuilder input = new StringBuilder();
    input.append("User request (Korean):\n").append(userPrompt).append('\n');

    String context = buildProjectContext(project);
    if (context != null) {
      input.append('\n').append(context);
    }
    input.append("\nReturn the English prompt only.");

    LlmCallContext ctx =
        new LlmCallContext(
            List.of(new LlmCallContext.Turn(MessageRole.SYSTEM, SYSTEM_INSTRUCTION)),
            input.toString(),
            null,
            null);

    String raw;
    try {
      LlmCallResult result = llm.generate(ctx);
      raw = result.content() == null ? "" : result.content().trim();
    } catch (Exception e) {
      log.warn(
          "PromptTranslator LLM 호출 실패, 원문 사용: prompt_length={}, error_class={}",
          userPrompt.length(),
          e.getClass().getSimpleName());
      persistLog(user, project, userPrompt, null, PromptTranslationLog.Status.FAILED, e.getMessage());
      return userPrompt;
    }

    if (raw.isBlank()) {
      log.warn(
          "PromptTranslator 가 빈 결과를 돌려줘서 원본을 사용합니다. prompt_length={}",
          userPrompt.length());
      persistLog(
          user, project, userPrompt, null, PromptTranslationLog.Status.FALLBACK_RAW, "empty result");
      return userPrompt;
    }

    String translated = parseJsonPrompt(raw);
    if (translated == null) {
      translated = sanitize(raw);
      log.warn(
          "PromptTranslator JSON 파싱 실패, sanitize fallback 사용: raw_length={}, translated_length={}",
          raw.length(),
          translated.length());
    }

    if (translated.isBlank()) {
      log.warn(
          "PromptTranslator 정제 결과가 비어있어 원본을 사용합니다. raw_length={}", raw.length());
      persistLog(
          user,
          project,
          userPrompt,
          null,
          PromptTranslationLog.Status.FALLBACK_RAW,
          "sanitize empty");
      return userPrompt;
    }

    // 원문·변환 결과 모두 길이만 기록. 영문 프롬프트도 사용자 의도가 그대로 반영돼 PII 추적 가능성 있음.
    // 디버깅 필요 시 prompt_translation_logs 테이블 (접근 권한 분리) 사용.
    log.info(
        "프롬프트 변환 완료: ko_length={}, en_length={}", userPrompt.length(), translated.length());
    persistLog(user, project, userPrompt, translated, PromptTranslationLog.Status.SUCCESS, null);
    return translated;
  }

  private void persistLog(
      User user,
      Project project,
      String koPrompt,
      String enPrompt,
      PromptTranslationLog.Status status,
      String errorMessage) {
    try {
      PromptTranslationLog logEntry = new PromptTranslationLog();
      logEntry.setUser(user);
      logEntry.setProject(project);
      logEntry.setKoPrompt(koPrompt);
      logEntry.setEnPrompt(enPrompt);
      logEntry.setStatus(status);
      logEntry.setErrorMessage(truncate(errorMessage, 1000));
      if (project != null) {
        logEntry.setProjectSubject(project.getSubject());
        logEntry.setProjectTechnique(project.getTechnique());
        logEntry.setProjectMood(project.getMood());
      }
      translationLogRepository.save(logEntry);
    } catch (Exception e) {
      log.warn("PromptTranslationLog 저장 실패 — 변환 결과에 영향 없음: {}", e.getMessage());
    }
  }

  private String truncate(String s, int max) {
    if (s == null) {
      return null;
    }
    return s.length() > max ? s.substring(0, max) : s;
  }

  private String parseJsonPrompt(String raw) {
    String candidate = CODE_FENCE.matcher(raw).replaceAll("").trim();
    int start = candidate.indexOf('{');
    int end = candidate.lastIndexOf('}');
    if (start < 0 || end <= start) {
      return null;
    }
    String jsonSlice = candidate.substring(start, end + 1);
    try {
      JsonNode node = objectMapper.readTree(jsonSlice);
      JsonNode promptNode = node.get("prompt");
      if (promptNode == null || !promptNode.isTextual()) {
        return null;
      }
      return promptNode.asText().trim();
    } catch (Exception e) {
      return null;
    }
  }

  private String sanitize(String raw) {
    String s = CODE_FENCE.matcher(raw).replaceAll("").trim();
    s = LEADING_PREFIX.matcher(s).replaceFirst("").trim();
    if (s.length() >= 2) {
      char first = s.charAt(0);
      char last = s.charAt(s.length() - 1);
      if ((first == '"' || first == '\'' || first == '`') && first == last) {
        s = s.substring(1, s.length() - 1).trim();
      }
    }
    return s;
  }

  private LlmService pickService() {
    return llmServices.stream()
        .filter(s -> s.provider() == TRANSLATOR_PROVIDER)
        .findFirst()
        .orElseThrow(() -> new CustomException(ErrorCode.AI_SERVICE_ERROR));
  }

  private String buildProjectContext(Project project) {
    if (project == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder("Project context:\n");
    boolean any = false;
    if (notBlank(project.getSubject())) {
      sb.append("- subject: ").append(project.getSubject()).append('\n');
      any = true;
    }
    if (notBlank(project.getTechnique())) {
      sb.append("- technique/style: ").append(project.getTechnique()).append('\n');
      any = true;
    }
    if (notBlank(project.getMood())) {
      sb.append("- mood: ").append(project.getMood()).append('\n');
      any = true;
    }
    return any ? sb.toString() : null;
  }

  private boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }
}
