package com.drawe.backend.domain.llm.repository;

import com.drawe.backend.domain.ChatSession;
import com.drawe.backend.domain.Project;
import com.drawe.backend.domain.User;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {

  List<ChatSession> findByProject(Project project);

  Optional<ChatSession> findTopByUserAndProjectIdOrderByLastActiveDesc(User user, Long projectId);

    /**
     * 마지막 활동 시각이 cutoff 이전인 모든 세션.
     *
     * <p>S2' Phase 6 Layer 0 SessionCleanupScheduler 가 사용.
     */
    List<ChatSession> findAllByLastActiveBefore(Instant cutoff);
}
