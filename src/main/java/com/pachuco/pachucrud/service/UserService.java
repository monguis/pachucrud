package com.pachuco.pachucrud.service;

import com.pachuco.pachucrud.model.UserRole;
import com.pachuco.pachucrud.repository.entity.UserEntity;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface UserService {
    UserEntity getUser(UUID id);
    UserEntity getUserByAuthId(String authId);
    UserEntity getUserByEmail(String email);
    UserEntity addPlayerUser(String authId, String email);
    UserEntity addTestPlayer(String username, String nickname, BigDecimal startingBalance);
    List<UserEntity> listUsersByRole(UserRole role);
    UserEntity updateUser(UUID id, String nickname, String username, String email);
    void deleteUser(UUID id);
    BigDecimal getBalance(UUID userId);
    void deposit(UUID userId, BigDecimal amount);
}
