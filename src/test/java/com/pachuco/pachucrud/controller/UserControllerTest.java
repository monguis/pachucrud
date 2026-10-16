package com.pachuco.pachucrud.controller;

import com.pachuco.pachucrud.model.UserRole;
import com.pachuco.pachucrud.repository.UserRepository;
import com.pachuco.pachucrud.repository.entity.UserEntity;
import com.pachuco.pachucrud.service.EventService;
import com.pachuco.pachucrud.service.RedisService;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pachuco_proto.Users;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock UserRepository userRepository;
    @Mock RedisService redisService;
    @Mock EventService eventService;

    private UserController controller;

    @BeforeEach
    void setUp() {
        controller = new UserController(userRepository, redisService, eventService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void addTestPlayerPersistsRolesAndSeedsBalance() {
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenAnswer(inv -> {
            UserEntity u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
        when(redisService.getBalance(any(UUID.class))).thenReturn(Optional.of(new BigDecimal("2500")));
        StreamObserver<Users.UserResponse> observer = mock(StreamObserver.class);

        controller.addTestPlayer(Users.UserRequest.newBuilder().setBalance(2500).build(), observer);

        ArgumentCaptor<UserEntity> saved = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).saveAndFlush(saved.capture());
        UserEntity entity = saved.getValue();
        assertEquals(List.of(UserRole.PLAYER, UserRole.TEST_PLAYER), entity.getRoles());
        assertTrue(entity.getAuthId().startsWith("test-player|"));
        assertTrue(entity.getUsername().startsWith("test_"));
        assertEquals(entity.getUsername(), entity.getNickname());

        ArgumentCaptor<BigDecimal> seeded = ArgumentCaptor.forClass(BigDecimal.class);
        verify(redisService).setBalance(any(UUID.class), seeded.capture());
        assertEquals(0, new BigDecimal("2500").compareTo(seeded.getValue()));

        ArgumentCaptor<Users.UserResponse> response = ArgumentCaptor.forClass(Users.UserResponse.class);
        verify(observer).onNext(response.capture());
        assertEquals(2500.0, response.getValue().getBalance());
        assertEquals(List.of("PLAYER", "TEST_PLAYER"), response.getValue().getRolesList());
    }
}
