package com.patipp.auth.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Callers must pass an already-normalised address; see {@link User#normaliseEmail}. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
