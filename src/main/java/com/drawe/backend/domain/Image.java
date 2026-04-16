package com.drawe.backend.domain;

import com.drawe.backend.domain.enums.ImageSource;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "images",
        indexes={
        @Index(name = "idx_img_src_srcId", columnList = "source, source_id")})
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

    @Column(name = "is_onboarding", nullable = false)
    @ColumnDefault("false")
    private Boolean isOnboarding = false;

    @Column(name = "is_tagged", nullable = false)
    @ColumnDefault("false")
    private Boolean isTagged = false;

    @Column(name = "raw_tags")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> rawTags;

}