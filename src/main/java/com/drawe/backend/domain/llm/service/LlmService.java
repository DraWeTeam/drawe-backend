package com.drawe.backend.domain.llm.service;

import com.drawe.backend.domain.enums.LlmProvider;
import com.drawe.backend.domain.llm.dto.LlmCallContext;
import com.drawe.backend.domain.llm.dto.LlmCallResult;

public interface LlmService {

  LlmProvider provider();

  LlmCallResult generate(LlmCallContext context);
}
