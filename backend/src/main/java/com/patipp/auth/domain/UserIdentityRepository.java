package com.patipp.auth.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

    /**
     * The only lookup sign-in uses. Keyed on the provider's immutable subject, never on an
     * email address - see {@link UserIdentity} for why that distinction is load-bearing.
     */
    Optional<UserIdentity> findByProviderAndExternalId(AuthProvider provider, String externalId);

    List<UserIdentity> findByUserId(UUID userId);
}
