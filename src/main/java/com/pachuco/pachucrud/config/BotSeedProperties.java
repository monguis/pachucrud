package com.pachuco.pachucrud.config;

import java.math.BigDecimal;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "bots.seed")
public class BotSeedProperties {

    private boolean enabled = false;
    private int count = 0;
    private int startIndex = 1;
    private BigDecimal balance = BigDecimal.valueOf(1000.0);
}