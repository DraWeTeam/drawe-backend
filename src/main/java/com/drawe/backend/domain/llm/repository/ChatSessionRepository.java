package com.drawe.backend.domain.llm.repository;

import com.drawe.backend.domain.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {}
