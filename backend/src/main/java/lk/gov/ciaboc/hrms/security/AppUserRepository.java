package lk.gov.ciaboc.hrms.security;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsernameIgnoreCase(String username);

    Optional<AppUser> findByEmployeeId(Long employeeId);

    boolean existsByUsernameIgnoreCase(String username);

    @Query("select distinct u from AppUser u join u.roles r where r.code = :roleCode and u.active = true and r.active = true")
    List<AppUser> findActiveByRole(@Param("roleCode") String roleCode);

    @Query("select u from AppUser u where (:q is null or lower(u.username) like lower(concat('%', :q, '%')) " +
            "or lower(u.fullName) like lower(concat('%', :q, '%')))")
    Page<AppUser> search(@Param("q") String q, Pageable pageable);
}
