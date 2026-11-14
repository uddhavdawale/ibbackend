package org.ib.ibbackend.repository;

import org.ib.ibbackend.entity.RefreshToken;
import org.ib.ibbackend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    Optional<RefreshToken> findByUserAndTokenHash(User user, String tokenHash);

    List<RefreshToken> findByUserIdAndRevokedFalse(Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update RefreshToken t set t.revoked = true, t.revokedAt = :revokedAt where t.user.id = :userId and t.revoked = false")
    int revokeAllActiveForUser(@Param("userId") Long userId, @Param("revokedAt") LocalDateTime revokedAt);
}
