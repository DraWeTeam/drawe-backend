package com.drawe.backend.domain;

import com.drawe.backend.domain.enums.ProjectStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(
    name = "projects",
    indexes = {@Index(name = "idx_proj_user_status", columnList = "user_id, status")})
public class Project {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Size(max = 100)
  @NotNull
  @Column(name = "name", nullable = false, length = 100)
  private String name;

  @Size(max = 100)
  @Column(name = "subject", length = 100)
  private String subject;

  @Lob
  @Column(name = "description")
  private String description;

  @Size(max = 30)
  @Column(name = "technique", length = 30)
  private String technique;

  @Size(max = 30)
  @Column(name = "mood", length = 30)
  private String mood;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private ProjectStatus status = ProjectStatus.IN_PROGRESS;

  @Size(max = 500)
  @Column(name = "drawing_url", length = 500)
  private String drawingUrl;

  @Column(name = "detail_answers")
  @JdbcTypeCode(SqlTypes.JSON)
  private Map<String, Object> detailAnswers;

  @Column(name = "suggestions_shown", nullable = false)
  @ColumnDefault("false")
  private Boolean suggestionsShown = false;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at")
  @UpdateTimestamp
  private Instant updatedAt;
}
