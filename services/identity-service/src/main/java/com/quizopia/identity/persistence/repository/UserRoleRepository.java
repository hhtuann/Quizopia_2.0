package com.quizopia.identity.persistence.repository;

import com.quizopia.identity.persistence.entity.UserRoleEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleRepository extends JpaRepository<UserRoleEntity, UUID> {
    List<UserRoleEntity> findAllByUser_Id(UUID userId);
}
