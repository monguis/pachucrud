package com.pachuco.pachucrud.service.impl;

import com.pachuco.pachucrud.exception.NotFoundException;
import com.pachuco.pachucrud.model.EventType;
import com.pachuco.pachucrud.model.UserRole;
import com.pachuco.pachucrud.repository.UserRepository;
import com.pachuco.pachucrud.repository.entity.UserEntity;
import com.pachuco.pachucrud.service.BalanceCache;
import com.pachuco.pachucrud.service.EventService;
import com.pachuco.pachucrud.service.RedisService;
import com.pachuco.pachucrud.service.UserService;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final RedisService redisService;
    private final EventService eventService;
    private final BalanceCache balanceCache;
    private final boolean testPlayersEnabled;

    public UserServiceImpl(UserRepository userRepository,
                           RedisService redisService,
                           EventService eventService,
                           BalanceCache balanceCache,
                           @Value("${test-players.enabled:false}") boolean testPlayersEnabled) {
        this.userRepository = userRepository;
        this.redisService = redisService;
        this.eventService = eventService;
        this.balanceCache = balanceCache;
        this.testPlayersEnabled = testPlayersEnabled;
    }

    @Override
    @Transactional(readOnly = true)
    public UserEntity getUser(UUID id) {
        return userRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("User not found: " + id));
    }

    @Override
    @Transactional(readOnly = true)
    public UserEntity getUserByAuthId(String authId) {
        return userRepository.findByAuthId(authId)
            .orElseThrow(() -> new NotFoundException("User not found for authId"));
    }

    @Override
    @Transactional(readOnly = true)
    public UserEntity getUserByEmail(String email) {
        return userRepository.findByEmail(email)
            .orElseThrow(() -> new NotFoundException("User not found for email"));
    }

    @Override
    @Transactional
    public UserEntity addPlayerUser(String authId, String email) {
        Optional<UserEntity> existing = userRepository.findByAuthId(authId);
        if (existing.isPresent()) {
            return existing.get();
        }

        UserEntity created = new UserEntity();
        created.setAuthId(authId);
        created.setEmail(email);
        created.setRoles(List.of(UserRole.PLAYER));
        UserEntity entity = userRepository.saveAndFlush(created);

        balanceCache.setAfterCommit(entity.getId(), BigDecimal.ZERO);
        return entity;
    }

    @Override
    @Transactional
    public UserEntity addTestPlayer(String username, String nickname, BigDecimal startingBalance) {
        if (!testPlayersEnabled) {
            throw new UnsupportedOperationException("Test Players are disabled (test-players.enabled=false)");
        }
        if (startingBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Balance must not be negative");
        }

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String resolvedUsername = username.isEmpty() ? "test_" + suffix : username;
        String resolvedNickname = nickname.isEmpty() ? resolvedUsername : nickname;
        String uniqueId = UUID.randomUUID().toString();

        UserEntity created = new UserEntity();
        created.setAuthId("test-player|" + uniqueId);
        created.setEmail("test-player-" + uniqueId + "@test.local");
        created.setUsername(resolvedUsername);
        created.setNickname(resolvedNickname);
        created.setRoles(List.of(UserRole.PLAYER, UserRole.TEST_PLAYER));
        UserEntity entity = userRepository.saveAndFlush(created);

        // The starting balance is a Deposit, so it survives the cache being flushed.
        if (startingBalance.signum() > 0) {
            recordDeposit(entity.getId(), startingBalance);
        }
        balanceCache.setAfterCommit(entity.getId(), startingBalance);
        return entity;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserEntity> listUsersByRole(UserRole role) {
        return userRepository.findByRole(role);
    }

    @Override
    @Transactional
    public UserEntity updateUser(UUID id, String nickname, String username, String email) {
        userRepository.findByEmail(email)
            .filter(u -> !u.getId().equals(id))
            .ifPresent(u -> { throw new IllegalArgumentException("Email already in use"); });

        UserEntity user = userRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("User not found"));

        if (!nickname.isEmpty()) user.setNickname(nickname);
        if (!username.isEmpty()) user.setUsername(username);
        if (!email.isEmpty()) user.setEmail(email);
        return userRepository.saveAndFlush(user);
    }

    @Override
    @Transactional
    public void deleteUser(UUID id) {
        userRepository.deleteById(id);
    }

    @Override
    public BigDecimal getBalance(UUID userId) {
        return redisService.getBalance(userId).orElseGet(() -> {
            BigDecimal computed = eventService.computeUserBalance(userId);
            redisService.setBalance(userId, computed);
            return computed;
        });
    }

    @Override
    @Transactional
    public void deposit(UUID userId, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Deposit amount must be positive");
        }
        recordDeposit(userId, amount);
        balanceCache.addAfterCommit(userId, amount);
    }

    private void recordDeposit(UUID userId, BigDecimal amount) {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("amount", amount);
        eventData.put("type", "deposit");
        eventService.writeEvent(null, EventType.DEPOSIT, userId, eventData);
    }
}
