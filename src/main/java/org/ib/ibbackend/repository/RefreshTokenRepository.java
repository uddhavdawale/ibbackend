package org.ib.ibbackend.repository;

import org.ib.ibbackend.entity.RefreshToken;
import org.ib.ibbackend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    Optional<RefreshToken> findByUserAndTokenHash(User user, String tokenHash);

    List<RefreshToken> findByUserIdAndRevokedFalse(Long userId);
}
