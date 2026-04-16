package com.drawe.backend.domain;

import com.drawe.backend.domain.enums.FeedbackType;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "image_feedback",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "image_id"}))
public class ImageFeedback {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "image_id", nullable = false)
    private Image image;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "feedback", nullable = false)
    private FeedbackType feedback;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

}