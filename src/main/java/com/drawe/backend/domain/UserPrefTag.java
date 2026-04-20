package com.drawe.backend.domain;

import com.drawe.backend.domain.enums.Axis;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
    name = "user_pref_tags",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_user_axis_value",
            columnNames = {"user_id", "axis", "value"}),
    indexes = {@Index(name = "idx_user_pref_tag_weight", columnList = "user_id, weight")})
public class UserPrefTag {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @NotNull
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @NotNull
  @Enumerated(EnumType.STRING)
  @Column(name = "axis", nullable = false)
  private Axis axis;

  @Size(max = 30)
  @NotNull
  @Column(name = "value", nullable = false, length = 30)
  private String value;

  @Column(name = "weight", nullable = false)
  private int weight = 1;
}
