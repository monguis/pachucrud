package com.pachuco.pachucrud.service;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class BalanceCacheTest {

    @Mock RedisService redisService;

    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void writesOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        BalanceCache cache = new BalanceCache(redisService);

        cache.setAfterCommit(userId, BigDecimal.TEN);
        verifyNoInteractions(redisService);

        TransactionSynchronizationManager.getSynchronizations()
            .forEach(TransactionSynchronization::afterCommit);
        verify(redisService).setBalance(userId, BigDecimal.TEN);
    }

    @Test
    void neverWritesWhenTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();
        BalanceCache cache = new BalanceCache(redisService);

        cache.addAfterCommit(userId, BigDecimal.TEN);
        TransactionSynchronizationManager.getSynchronizations()
            .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(redisService);
    }

    @Test
    void writesImmediatelyOutsideTransaction() {
        new BalanceCache(redisService).addAfterCommit(userId, BigDecimal.ONE);

        verify(redisService).incrementBalanceIfPresent(userId, BigDecimal.ONE);
    }

    @Test
    void evictsKeyWhenWriteFails() {
        doThrow(new IllegalStateException("redis down"))
            .when(redisService).setBalance(userId, BigDecimal.TEN);

        new BalanceCache(redisService).setAfterCommit(userId, BigDecimal.TEN);

        verify(redisService).deleteBalance(userId);
    }
}
