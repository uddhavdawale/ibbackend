package org.ib.ibbackend.repository;

import org.ib.ibbackend.entity.RefreshToken;
import org.ib.ibbackend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    Optional<RefreshToken> findByUserAndTokenHash(User user, String tokenHash);

    List<RefreshToken> findByUserIdAndRevokedFalse(Long userId);

    boolean existsByUserIdAndRevokedFalse(Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update RefreshToken t set t.revoked = true, t.revokedAt = coalesce(t.revokedAt, :revokedAt), "
            + "t.replacementTokenCiphertext = null, t.replacementGraceUntil = null "
            + "where t.user.id = :userId and (t.revoked = false or t.replacementTokenCiphertext is not null)")
    int revokeAllActiveForUser(@Param("userId") Long userId, @Param("revokedAt") LocalDateTime revokedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update RefreshToken t set t.revoked = true, t.revokedAt = coalesce(t.revokedAt, :revokedAt), "
            + "t.replacementTokenCiphertext = null, t.replacementGraceUntil = null "
            + "where t.familyId = :familyId")
    int revokeFamily(@Param("familyId") String familyId, @Param("revokedAt") LocalDateTime revokedAt);
}
