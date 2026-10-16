package com.pachuco.pachucrud.repository;

import com.pachuco.pachucrud.model.UserRole;
import com.pachuco.pachucrud.repository.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, UUID> {
    Optional<UserEntity> findByEmail(String email);
    Optional<UserEntity> findByAuthId(String authId);

    @Query("SELECT u FROM UserEntity u WHERE :role MEMBER OF u.roles")
    List<UserEntity> findByRole(@Param("role") UserRole role);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserEntity u SET u.nickname = :nickname, u.username = :username WHERE u.id = :id")
    int updateUserFields(@Param("id") UUID id, @Param("nickname") String nickname, @Param("username") String username, @Param("email") String email);

    @Modifying
    @Query("DELETE FROM UserEntity u WHERE u.id = :id")
    int deleteUserById(@Param("id") UUID id);
}
