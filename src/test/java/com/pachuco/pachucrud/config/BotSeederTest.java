package com.pachuco.pachucrud.config;

import com.pachuco.pachucrud.model.EventType;
import com.pachuco.pachucrud.model.UserRole;
import com.pachuco.pachucrud.repository.UserRepository;
import com.pachuco.pachucrud.repository.entity.UserEntity;
import com.pachuco.pachucrud.service.EventService;
import com.pachuco.pachucrud.service.RedisService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotSeederTest {

    @Mock UserRepository userRepository;
    @Mock EventService eventService;
    @Mock RedisService redisService;

    private BotSeedProperties properties;
    private BotSeeder seeder;

    @BeforeEach
    void setUp() {
        properties = new BotSeedProperties();
        seeder = new BotSeeder(properties, userRepository, eventService, redisService);
    }

    private UserEntity newUser(String authId) {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setAuthId(authId);
        return user;
    }

    private void stubCreateUsers() {
        when(userRepository.findByAuthId(anyString())).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenAnswer(inv -> {
            UserEntity u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
        when(redisService.getBalance(any(UUID.class))).thenReturn(Optional.empty());
    }

    @Test
    void run_doesNothingWhenDisabled() {
        properties.setEnabled(false);
        properties.setCount(3);

        seeder.run(new DefaultApplicationArguments());

        verifyNoInteractions(userRepository, eventService, redisService);
    }

    @Test
    void run_doesNothingWhenCountZero() {
        properties.setEnabled(true);
        properties.setCount(0);

        seeder.run(new DefaultApplicationArguments());

        verifyNoInteractions(userRepository, eventService, redisService);
    }

    @Test
    void run_createsThreeBotsWithConventionAndDeposit() {
        properties.setEnabled(true);
        properties.setCount(3);
        properties.setStartIndex(1);
        properties.setBalance(BigDecimal.valueOf(1000));
        stubCreateUsers();

        seeder.run(new DefaultApplicationArguments());

        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository, times(3)).saveAndFlush(userCaptor.capture());
        List<String> authIds = userCaptor.getAllValues().stream()
            .map(UserEntity::getAuthId).sorted().toList();
        assertEquals(List.of("bot|001", "bot|002", "bot|003"), authIds);

        List<String> usernames = userCaptor.getAllValues().stream()
            .map(UserEntity::getUsername).sorted().toList();
        assertEquals(List.of("bot_001", "bot_002", "bot_003"), usernames);

        List<String> nicknames = userCaptor.getAllValues().stream()
            .map(UserEntity::getNickname).sorted().toList();
        assertEquals(List.of("Bot 001", "Bot 002", "Bot 003"), nicknames);

        for (UserEntity u : userCaptor.getAllValues()) {
            assertEquals("bot_" + u.getAuthId().substring(4) + "@pachuco.ai", u.getEmail());
            assertEquals(List.of(UserRole.PLAYER), u.getRoles());
        }

        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService, times(3))
            .writeEvent(isNull(), eq(EventType.DEPOSIT), any(UUID.class), dataCaptor.capture());
        for (Map<String, Object> data : dataCaptor.getAllValues()) {
            assertEquals(BigDecimal.valueOf(1000), data.get("amount"));
            assertEquals("deposit", data.get("type"));
        }

        verify(redisService, times(3)).setBalance(any(UUID.class), eq(BigDecimal.valueOf(1000)));
    }

    @Test
    void run_respectsStartIndexOffset() {
        properties.setEnabled(true);
        properties.setCount(2);
        properties.setStartIndex(5);
        stubCreateUsers();

        seeder.run(new DefaultApplicationArguments());

        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository, times(2)).saveAndFlush(userCaptor.capture());
        List<String> authIds = userCaptor.getAllValues().stream()
            .map(UserEntity::getAuthId).sorted().toList();
        assertEquals(List.of("bot|005", "bot|006"), authIds);
    }

    @Test
    void run_skipsExistingBotsWithoutDeposit() {
        properties.setEnabled(true);
        properties.setCount(3);
        stubCreateUsers();

        when(userRepository.findByAuthId("bot|002"))
            .thenReturn(Optional.of(newUser("bot|002")));

        seeder.run(new DefaultApplicationArguments());

        verify(userRepository, times(2)).saveAndFlush(any(UserEntity.class));
        verify(eventService, times(2)).writeEvent(isNull(), eq(EventType.DEPOSIT), any(UUID.class), anyMap());
        verify(redisService, times(2)).setBalance(any(UUID.class), any(BigDecimal.class));

        ArgumentCaptor<String> lookupCaptor = ArgumentCaptor.forClass(String.class);
        verify(userRepository, times(3)).findByAuthId(lookupCaptor.capture());
        assertTrue(lookupCaptor.getAllValues().contains("bot|002"));
    }

    @Test
    void run_seedsBotWithZeroBalance() {
        properties.setEnabled(true);
        properties.setCount(1);
        properties.setBalance(BigDecimal.ZERO);
        stubCreateUsers();

        seeder.run(new DefaultApplicationArguments());

        verify(userRepository, times(1)).saveAndFlush(any(UserEntity.class));
        verify(eventService, times(1)).writeEvent(isNull(), eq(EventType.DEPOSIT), any(UUID.class), anyMap());
        verify(redisService, times(1)).setBalance(any(UUID.class), eq(BigDecimal.ZERO));
    }
}