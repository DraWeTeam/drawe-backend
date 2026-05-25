package com.drawe.backend.domain.log;

import com.drawe.backend.domain.Project;
import com.drawe.backend.domain.User;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Getter
@Setter
@Entity
@Table(
    name = "prompt_translation_logs",
    indexes = {
      @Index(name = "idx_ptl_user_created", columnList = "user_id, created_at"),
      @Index(name = "idx_ptl_created", columnList = "created_at")
    })
public class PromptTranslationLog {

  public enum Status {
    SUCCESS,
    FALLBACK_RAW,
    FAILED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = true)
  @OnDelete(action = OnDeleteAction.SET_NULL)
  private User user;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "project_id", nullable = true)
  @OnDelete(action = OnDeleteAction.SET_NULL)
  private Project project;

  @Column(name = "ko_prompt", nullable = false, columnDefinition = "text")
  private String koPrompt;

  @Column(name = "en_prompt", columnDefinition = "text")
  private String enPrompt;

  @Column(name = "project_subject", length = 255)
  private String projectSubject;

  @Column(name = "project_technique", length = 255)
  private String projectTechnique;

  @Column(name = "project_mood", length = 255)
  private String projectMood;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private Status status;

  @Column(name = "error_message", length = 1000)
  private String errorMessage;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;
}
