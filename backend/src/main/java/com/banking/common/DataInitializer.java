package com.banking.common;

import com.banking.user.entity.Role;
import com.banking.user.entity.User;
import com.banking.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        seedUsers();
    }

    private void seedUsers() {
        List<UserSeed> seeds = List.of(
            new UserSeed("Admin User",  "admin@banking.com",  "Admin1234!",  Role.ADMIN),
            new UserSeed("Alice Smith", "alice@banking.com",  "Alice1234!",  Role.USER),
            new UserSeed("Bob Jones",   "bob@banking.com",    "Bob1234!",    Role.USER)
        );

        for (UserSeed seed : seeds) {
            if (userRepository.existsByEmail(seed.email())) {
                log.info("Skipping existing user: {}", seed.email());
                continue;
            }
            userRepository.save(User.builder()
                .fullName(seed.fullName())
                .email(seed.email())
                .password(passwordEncoder.encode(seed.password()))
                .role(seed.role())
                .build());
            log.info("Created default user: {}", seed.email());
        }
    }

    private record UserSeed(String fullName, String email, String password, Role role) {}
}