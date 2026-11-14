package org.ib.ibbackend.repository;

import jakarta.persistence.LockModeType;
import org.ib.ibbackend.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PasswordResetToken t where t.tokenDigest = :digest")
    Optional<PasswordResetToken> findByDigestForUpdate(@Param("digest") String digest);

    @Query("""
            select count(t) > 0 from PasswordResetToken t
            where t.user.id = :userId and t.issuedAt > :since
            """)
    boolean wasRecentlyIssued(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PasswordResetToken t set t.consumedAt = :now where t.user.id = :userId and t.consumedAt is null")
    int consumeOutstandingForUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
