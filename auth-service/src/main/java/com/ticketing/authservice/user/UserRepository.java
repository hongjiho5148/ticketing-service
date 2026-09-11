package com.ticketing.authservice.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByProviderAndEmail(AuthProvider provider, String email);

    Optional<User> findByProviderAndProviderId(AuthProvider provider, String providerId);

    Optional<User> findByEmailVerificationToken(String emailVerificationToken);

    boolean existsByProviderAndEmail(AuthProvider provider, String email);
}
