package com.drawe.backend.domain.log;

import com.drawe.backend.domain.Project;
import com.drawe.backend.domain.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(
        name = "search_logs",
        indexes = {
                @Index(name = "idx_search_log_user", columnList = "user_id"),
                @Index(name = "idx_search_log_created", columnList = "created_at")
        }
)
public class SearchLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @Column(name = "original_message", length = 1000)
    private String originalMessage;

    @Column(name = "extracted_keywords", length = 500)
    private String extractedKeywords;

    @Column(name = "result_count")
    private Integer resultCount;

    @Column(name = "avg_score")
    private Double avgScore;

    @Column(name = "source", length = 30)
    private String source;
    // "rag_chat" / "manual_search" 구분

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
