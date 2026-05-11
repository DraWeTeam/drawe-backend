package com.drawe.backend.domain.onboarding;

import com.drawe.backend.domain.User;
import com.drawe.backend.domain.UserPrefTag;
import com.drawe.backend.domain.enums.Axis;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface UserPrefTagRepository extends JpaRepository<UserPrefTag, Long> {

  List<UserPrefTag> findByUser(User user);

  List<UserPrefTag> findByUserAndAxis(User user, Axis axis);

  boolean existsByUser(User user);

  @Transactional
  void deleteByUser(User user);
}
