package com.drawe.backend.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "project_references",
        uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "image_id"}))
public class ProjectReference {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "image_id", nullable = false)
    private Image image;

    @CreationTimestamp
    @Column(name = "added_at", updatable = false)
    private Instant addedAt;

}