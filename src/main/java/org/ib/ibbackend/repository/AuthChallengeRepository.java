package org.ib.ibbackend.repository;

import jakarta.persistence.LockModeType;
import org.ib.ibbackend.entity.AuthChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AuthChallengeRepository extends JpaRepository<AuthChallenge, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AuthChallenge c where c.id = :id")
    Optional<AuthChallenge> findByIdForUpdate(@Param("id") String id);
}
