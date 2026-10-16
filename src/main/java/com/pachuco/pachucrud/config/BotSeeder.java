package com.pachuco.pachucrud.config;

import com.pachuco.pachucrud.model.EventType;
import com.pachuco.pachucrud.model.UserRole;
import com.pachuco.pachucrud.repository.UserRepository;
import com.pachuco.pachucrud.repository.entity.UserEntity;
import com.pachuco.pachucrud.service.EventService;
import com.pachuco.pachucrud.service.BalanceCache;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BotSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BotSeeder.class);

    private static final String AUTH_PREFIX = "bot|";
    private static final String EMAIL_DOMAIN = "pachuco.ai";

    private final BotSeedProperties properties;
    private final UserRepository userRepository;
    private final EventService eventService;
    private final BalanceCache balanceCache;

    public BotSeeder(BotSeedProperties properties,
                     UserRepository userRepository,
                     EventService eventService,
                     BalanceCache balanceCache) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.eventService = eventService;
        this.balanceCache = balanceCache;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled() || properties.getCount() <= 0) {
            return;
        }

        int created = 0;
        int skipped = 0;
        for (int i = properties.getStartIndex();
             i < properties.getStartIndex() + properties.getCount(); i++) {
            if (seedBot(i)) {
                created++;
            } else {
                skipped++;
            }
        }
        log.info("Bot seeding complete: {} created, {} skipped (existing)", created, skipped);
    }

    private boolean seedBot(int index) {
        String idx = String.format("%03d", index);
        String authId = AUTH_PREFIX + idx;
        String email = "bot_" + idx + "@" + EMAIL_DOMAIN;
        String username = "bot_" + idx;
        String nickname = "Bot " + idx;

        if (userRepository.findByAuthId(authId).isPresent()) {
            log.info("Bot {} already exists, skipping", authId);
            return false;
        }

        UserEntity user = new UserEntity();
        user.setAuthId(authId);
        user.setEmail(email);
        user.setUsername(username);
        user.setNickname(nickname);
        user.setRoles(List.of(UserRole.PLAYER));
        user = userRepository.saveAndFlush(user);

        BigDecimal amount = properties.getBalance();
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("amount", amount);
        eventData.put("type", "deposit");

        eventService.writeEvent(null, EventType.DEPOSIT, user.getId(), eventData);

        balanceCache.setAfterCommit(user.getId(), amount);

        log.info("Seeded bot {} (id: {}) with balance {}", authId, user.getId(), amount);
        return true;
    }
}