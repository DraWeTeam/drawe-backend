package com.drawe.backend.domain.llm.repository;

import com.drawe.backend.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, Long> {}
