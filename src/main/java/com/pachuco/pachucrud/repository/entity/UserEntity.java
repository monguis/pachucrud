package com.pachuco.pachucrud.repository.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.pachuco.pachucrud.model.UserRole;

@Entity
@Table(name = "users")
@Data
public class UserEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String authId;

    @Column(unique = true)
    private String username;

    private String nickname;

    @Column(unique = true, nullable = false)
    private String email;

    private BigDecimal balance;

    @ElementCollection
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private List<UserRole> roles = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "join_date", nullable = false, updatable = false)
    private Instant joinDate;
}
