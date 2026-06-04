package com.gte619n.healthfitness.core.user;

import com.gte619n.healthfitness.core.auth.CurrentUser;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    public void provisionIfAbsent(CurrentUser current) {
        if (users.findById(current.userId()).isPresent()) {
            return;
        }
        Instant now = Instant.now();
        users.save(new User(
            current.userId(),
            current.email(),
            current.displayName(),
            null,
            null,
            now,
            now
        ));
    }
}
