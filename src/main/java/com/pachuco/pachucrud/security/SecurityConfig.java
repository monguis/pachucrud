package com.pachuco.pachucrud.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 1. MUST disable CSRF for gRPC
                .csrf(csrf -> csrf.disable())
                // 2. Allow the gRPC protocol paths
                .authorizeHttpRequests(auth -> auth
                        // .requestMatchers("/pachuco_proto.User/**").permitAll()
                        .anyRequest()
                        .permitAll()
                        // .authenticated()
                    );

        return http.build();
    }
}