package com.pachuco.pachucrud.repository;

import com.pachuco.pachucrud.model.UserRole;
import com.pachuco.pachucrud.repository.entity.UserEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
@ActiveProfiles("test")
class UserEntityRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldSaveAndRetrieveAllFields() {
        UserEntity user = new UserEntity();
        user.setAuthId("auth|123");
        user.setUsername("johndoe");
        user.setNickname("Johnny");
        user.setEmail("john@example.com");
        user.setBalance(new BigDecimal("100.50"));
        user.setRoles(List.of(UserRole.PLAYER, UserRole.ADMIN));
        user.setJoinDate(Instant.parse("2025-01-01T00:00:00Z"));

        UserEntity saved = userRepository.save(user);
        UserEntity found = userRepository.findById(saved.getId()).orElseThrow();

        assertEquals("auth|123", found.getAuthId());
        assertEquals("johndoe", found.getUsername());
        assertEquals("Johnny", found.getNickname());
        assertEquals("john@example.com", found.getEmail());
        assertEquals(0, new BigDecimal("100.50").compareTo(found.getBalance()));
        assertTrue(found.getRoles().containsAll(List.of(UserRole.PLAYER, UserRole.ADMIN)));
        assertEquals(Instant.parse("2025-01-01T00:00:00Z"), found.getJoinDate());
    }

    @Test
    void shouldEnforceUniqueEmail() {
        UserEntity user1 = new UserEntity();
        user1.setAuthId("auth|1");
        user1.setEmail("same@example.com");
        user1.setUsername("user1");
        userRepository.save(user1);

        UserEntity user2 = new UserEntity();
        user2.setAuthId("auth|2");
        user2.setEmail("same@example.com");
        user2.setUsername("user2");

        assertThrows(DataIntegrityViolationException.class, () -> userRepository.saveAndFlush(user2));
    }

    @Test
    void shouldEnforceUniqueAuthId() {
        UserEntity user1 = new UserEntity();
        user1.setAuthId("auth|unique");
        user1.setEmail("one@example.com");
        user1.setUsername("user1");
        userRepository.save(user1);

        UserEntity user2 = new UserEntity();
        user2.setAuthId("auth|unique");
        user2.setEmail("two@example.com");
        user2.setUsername("user2");

        assertThrows(DataIntegrityViolationException.class, () -> userRepository.saveAndFlush(user2));
    }

    @Test
    void shouldPersistRolesCollection() {
        UserEntity user = new UserEntity();
        user.setAuthId("auth|roles");
        user.setEmail("roles@example.com");
        user.setUsername("roleuser");
        user.setRoles(List.of(UserRole.ADMIN));

        userRepository.save(user);
        UserEntity found = userRepository.findByEmail("roles@example.com").orElseThrow();

        assertEquals(1, found.getRoles().size());
        assertTrue(found.getRoles().contains(UserRole.ADMIN));
    }

    @Test
    void shouldFindUserByAuthIdAndReturnRoles() {
        UserEntity user = new UserEntity();
        user.setAuthId("auth|controller-test");
        user.setEmail("ctest@example.com");
        user.setUsername("ctestuser");
        user.setRoles(List.of(UserRole.PLAYER));

        userRepository.save(user);
        UserEntity found = userRepository.findByAuthId("auth|controller-test").orElseThrow();

        assertEquals("ctestuser", found.getUsername());
        assertEquals("ctest@example.com", found.getEmail());
        assertTrue(found.getRoles().contains(UserRole.PLAYER));
        assertEquals(1, found.getRoles().size());
    }
}
