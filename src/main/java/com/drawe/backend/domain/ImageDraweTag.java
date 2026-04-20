package com.drawe.backend.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(
    name = "image_drawe_tags",
    indexes = {
      @Index(name = "idx_img_tag_tech", columnList = "technique"),
      @Index(name = "idx_img_tag_sbj", columnList = "subject"),
      @Index(name = "idx_img_tag_mood", columnList = "mood")
    })
public class ImageDraweTag {
  @Id
  @Column(name = "image_id", nullable = false)
  private Long id;

  @MapsId
  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "image_id", nullable = false)
  private Image image;

  @Size(max = 30)
  @Column(name = "technique", length = 30)
  private String technique;

  @Size(max = 30)
  @Column(name = "subject", length = 30)
  private String subject;

  @Size(max = 30)
  @Column(name = "mood", length = 30)
  private String mood;

  @Column(name = "free_tags")
  @JdbcTypeCode(SqlTypes.JSON)
  private List<String> freeTags;

  @Size(max = 20)
  @Column(name = "tagged_by", length = 20)
  private String taggedBy;

  @Column(name = "tagged_at")
  private Instant taggedAt;
}
