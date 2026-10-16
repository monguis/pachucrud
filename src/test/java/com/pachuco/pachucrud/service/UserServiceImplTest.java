package com.pachuco.pachucrud.service;

import com.pachuco.pachucrud.model.EventType;
import com.pachuco.pachucrud.model.UserRole;
import com.pachuco.pachucrud.repository.UserRepository;
import com.pachuco.pachucrud.repository.entity.UserEntity;
import com.pachuco.pachucrud.service.impl.UserServiceImpl;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock UserRepository userRepository;
    @Mock RedisService redisService;
    @Mock EventService eventService;
    @Mock BalanceCache balanceCache;

    private UserServiceImpl service(boolean testPlayersEnabled) {
        return new UserServiceImpl(userRepository, redisService, eventService, balanceCache,
            testPlayersEnabled);
    }

    private void saveAssignsId() {
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenAnswer(inv -> {
            UserEntity u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void addTestPlayerPersistsRolesAndFundsWithDeposit() {
        saveAssignsId();

        UserEntity entity = service(true).addTestPlayer("", "", new BigDecimal("2500"));

        assertEquals(List.of(UserRole.PLAYER, UserRole.TEST_PLAYER), entity.getRoles());
        assertTrue(entity.getAuthId().startsWith("test-player|"));
        assertTrue(entity.getUsername().startsWith("test_"));
        assertEquals(entity.getUsername(), entity.getNickname());

        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(eventService).writeEvent(isNull(), eq(EventType.DEPOSIT), eq(entity.getId()), data.capture());
        assertEquals(0, new BigDecimal("2500").compareTo((BigDecimal) data.getValue().get("amount")));
        verify(balanceCache).setAfterCommit(entity.getId(), new BigDecimal("2500"));
    }

    @Test
    void addTestPlayerWithZeroBalanceRecordsNoDeposit() {
        saveAssignsId();

        UserEntity entity = service(true).addTestPlayer("", "", BigDecimal.ZERO);

        verify(eventService, never()).writeEvent(any(), any(), any(), anyMap());
        verify(balanceCache).setAfterCommit(entity.getId(), BigDecimal.ZERO);
    }

    @Test
    void addTestPlayerIsUnimplementedWhenDisabled() {
        assertThrows(UnsupportedOperationException.class,
            () -> service(false).addTestPlayer("", "", BigDecimal.TEN));
        verifyNoInteractions(userRepository, eventService, balanceCache);
    }

    @Test
    void addTestPlayerRejectsNegativeBalance() {
        assertThrows(IllegalArgumentException.class,
            () -> service(true).addTestPlayer("", "", new BigDecimal("-1")));
        verifyNoInteractions(userRepository);
    }

    @Test
    void addPlayerUserSeedsZeroBalanceForNewPlayer() {
        when(userRepository.findByAuthId("auth|new")).thenReturn(Optional.empty());
        saveAssignsId();

        UserEntity entity = service(false).addPlayerUser("auth|new", "new@example.com");

        assertEquals(List.of(UserRole.PLAYER), entity.getRoles());
        verify(balanceCache).setAfterCommit(entity.getId(), BigDecimal.ZERO);
    }

    @Test
    void addPlayerUserKeepsReturningPlayersBalance() {
        UserEntity existing = new UserEntity();
        existing.setId(UUID.randomUUID());
        when(userRepository.findByAuthId("auth|back")).thenReturn(Optional.of(existing));

        assertSame(existing, service(false).addPlayerUser("auth|back", "back@example.com"));

        verify(userRepository, never()).saveAndFlush(any());
        verifyNoInteractions(balanceCache, redisService);
    }

    @Test
    void depositRecordsEventThenIncrementsCache() {
        UUID userId = UUID.randomUUID();

        service(false).deposit(userId, new BigDecimal("50"));

        verify(eventService).writeEvent(isNull(), eq(EventType.DEPOSIT), eq(userId), anyMap());
        verify(balanceCache).addAfterCommit(userId, new BigDecimal("50"));
        verify(redisService, never()).setBalance(any(), any());
    }

    @Test
    void depositRejectsNonPositiveAmount() {
        assertThrows(IllegalArgumentException.class,
            () -> service(false).deposit(UUID.randomUUID(), BigDecimal.ZERO));
        verifyNoInteractions(eventService, balanceCache);
    }

    @Test
    void getBalanceRecomputesFromEventsOnCacheMiss() {
        UUID userId = UUID.randomUUID();
        when(redisService.getBalance(userId)).thenReturn(Optional.empty());
        when(eventService.computeUserBalance(userId)).thenReturn(new BigDecimal("75"));

        assertEquals(new BigDecimal("75"), service(false).getBalance(userId));
        verify(redisService).setBalance(userId, new BigDecimal("75"));
    }
}
