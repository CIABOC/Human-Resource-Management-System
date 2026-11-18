package lk.gov.ciaboc.hrms.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface OtpChallengeRepository extends JpaRepository<OtpChallenge, String> {
    @Modifying
    @Query("delete from OtpChallenge c where c.expiresAt < :before")
    int deleteExpired(@Param("before") Instant before);
}
