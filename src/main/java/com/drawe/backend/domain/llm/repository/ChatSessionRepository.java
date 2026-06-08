package com.drawe.backend.domain.llm.repository;

import com.drawe.backend.domain.ChatSession;
import com.drawe.backend.domain.Project;
import com.drawe.backend.domain.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {

  List<ChatSession> findByProject(Project project);

  Optional<ChatSession> findTopByUserAndProjectIdOrderByLastActiveDesc(User user, Long projectId);
}
