package com.drawe.backend.domain;

import com.drawe.backend.domain.enums.LlmCallStatus;
import com.drawe.backend.domain.enums.LlmProvider;
import com.drawe.backend.domain.enums.MessageRole;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(
    name = "llm_messages",
    indexes = {
      @Index(name = "idx_llm_session_created", columnList = "chat_session_id, created_at")
    })
public class LlmMessage {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "chat_session_id", nullable = false)
  private ChatSession chatSession;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 20)
  private MessageRole role;

  @Lob
  @NotNull
  @Column(name = "content", nullable = false)
  private String content;

  @Enumerated(EnumType.STRING)
  @Column(name = "provider", length = 20)
  private LlmProvider provider;

  @Size(max = 100)
  @Column(name = "model", length = 100)
  private String model;

  @Column(name = "has_image", nullable = false)
  @ColumnDefault("false")
  private Boolean hasImage = false;

  // Phase 2: Cloudinary URL
  @Size(max = 500)
  @Column(name = "image_url", length = 500)
  private String imageUrl;

  // Phase 3: Pinecone embedding ID
  @Size(max = 100)
  @Column(name = "embedding_id", length = 100)
  private String embeddingId;

  // Phase 3: 검색된 레퍼런스 이미지 ID 목록
  @Column(name = "reference_ids")
  @JdbcTypeCode(SqlTypes.JSON)
  private List<Long> referenceIds;

  @Column(name = "latency_ms")
  private Integer latencyMs;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 20)
  private LlmCallStatus status;

  @Lob
  @Column(name = "error_message")
  private String errorMessage;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  private Instant createdAt;
}
