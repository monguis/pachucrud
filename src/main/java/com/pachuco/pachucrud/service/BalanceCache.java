package com.pachuco.pachucrud.service;

import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes cached balances only once the surrounding transaction has committed, so a
 * rollback can never leave Redis describing money the database doesn't have.
 * Events are the source of truth: when a write fails the key is dropped and the next
 * read recomputes it, instead of leaving a stale value behind.
 */
@Component
public class BalanceCache {

    private static final Logger log = LoggerFactory.getLogger(BalanceCache.class);

    private final RedisService redisService;
    private final EventService eventService;

    public BalanceCache(RedisService redisService, EventService eventService) {
        this.redisService = redisService;
        this.eventService = eventService;
    }

    /** The cached balance, or the balance recomputed from events (and cached) on a miss. */
    public BigDecimal get(UUID userId) {
        return redisService.getBalance(userId).orElseGet(() -> {
            BigDecimal computed = eventService.computeUserBalance(userId);
            redisService.setBalance(userId, computed);
            return computed;
        });
    }

    public void setAfterCommit(UUID userId, BigDecimal balance) {
        afterCommit(userId, () -> redisService.setBalance(userId, balance));
    }

    public void addAfterCommit(UUID userId, BigDecimal amount) {
        afterCommit(userId, () -> redisService.incrementBalanceIfPresent(userId, amount));
    }

    private void afterCommit(UUID userId, Runnable write) {
        Runnable guarded = () -> {
            try {
                write.run();
            } catch (RuntimeException e) {
                log.warn("Balance cache write failed for {}; evicting so it is recomputed", userId, e);
                evict(userId);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    guarded.run();
                }
            });
        } else {
            guarded.run();
        }
    }

    private void evict(UUID userId) {
        try {
            redisService.deleteBalance(userId);
        } catch (RuntimeException e) {
            log.error("Could not evict balance cache for {}; it may be stale", userId, e);
        }
    }
}
