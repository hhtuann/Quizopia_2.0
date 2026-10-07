package com.quizopia.identity.persistence.repository;

import com.quizopia.identity.persistence.entity.RoleGrantAuditEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleGrantAuditRepository extends JpaRepository<RoleGrantAuditEntity, UUID> {}
