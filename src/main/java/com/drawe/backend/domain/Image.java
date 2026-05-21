package com.drawe.backend.domain;

import com.drawe.backend.domain.enums.ImageSource;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(
    name = "images",
    indexes = {
      @Index(name = "idx_img_src_srcId", columnList = "source, source_id"),
      @Index(name = "idx_img_embedding", columnList = "embedding_id")
    })
public class Image {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false)
  private ImageSource source;

  @Size(max = 100)
  @Column(name = "source_id", length = 100)
  private String sourceId;

  @Size(max = 500)
  @NotNull
  @Column(name = "url", nullable = false, length = 500)
  private String url;

  @Size(max = 100)
  @Column(name = "embedding_id", length = 100)
  private String embeddingId;

  @Size(max = 100)
  @Column(name = "photographer_username", length = 100)
  private String photographerUsername;

  @Size(max = 200)
  @Column(name = "photographer_name", length = 200)
  private String photographerName;

  @Column(name = "is_onboarding", nullable = false)
  @ColumnDefault("false")
  private Boolean isOnboarding = false;

  @Column(name = "is_tagged", nullable = false)
  @ColumnDefault("false")
  private Boolean isTagged = false;

  @Column(name = "raw_tags")
  @JdbcTypeCode(SqlTypes.JSON)
  private List<String> rawTags;

  /** Bria 등 외부 모델에 보낸 영문 프롬프트. AI 이미지에서만 채워진다. */
  @Column(name = "prompt", columnDefinition = "text")
  private String prompt;

  /** AI 이미지의 생성자. 전체 공개 정책이지만 감사·필터링용으로 보관. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "created_by_user_id")
  @OnDelete(action = OnDeleteAction.SET_NULL)
  private User createdBy;

  /** Pinecone 적재 완료 시각. NULL = 미적재 (실패했거나 아직 비동기 처리 전). */
  @Column(name = "indexed_at")
  private Instant indexedAt;
}
