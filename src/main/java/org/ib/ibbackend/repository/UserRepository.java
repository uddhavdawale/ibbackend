package org.ib.ibbackend.repository;


import org.ib.ibbackend.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    @Query("select u from User u where lower(trim(u.username)) = lower(trim(:username)) and u.deletedAt is null")
    Optional<User> findByUsername(@Param("username") String username);

    @Query("select (count(u) > 0) from User u where lower(trim(u.username)) = lower(trim(:username)) and u.deletedAt is null")
    boolean existsByUsername(@Param("username") String username);

    @Query("select (count(u) > 0) from User u where lower(trim(u.username)) = lower(trim(:username)) and u.id <> :id and u.deletedAt is null")
    boolean existsByUsernameAndIdNot(@Param("username") String username, @Param("id") Long id);

    @Query("select u from User u where lower(trim(u.email)) = lower(trim(:email)) and u.deletedAt is null")
    Optional<User> findByEmail(@Param("email") String email);

    @Query("select (count(u) > 0) from User u where lower(trim(u.email)) = lower(trim(:email)) and u.deletedAt is null")
    boolean existsByEmail(@Param("email") String email);

    @Query("select u from User u where lower(u.ownerName) = lower(:ownerName) and u.deletedAt is null")
    Optional<User> findByOwnerName(@Param("ownerName") String ownerName);

    @Query("select u from User u where u.id = :id and u.tenant.id = :tenantId and u.deletedAt is null")
    Optional<User> findByIdAndTenant_Id(@Param("id") Long id, @Param("tenantId") int tenantId);

        @Query("""
                select u
                from User u
                where u.deletedAt is null
                    and (:includeDisabled = true or (coalesce(u.disabled, false) = false and lower(coalesce(u.role, '')) <> 'disabled_user'))
                    and (
                        :search is null
                        or :search = ''
                        or lower(coalesce(u.username, '')) like lower(concat('%', :search, '%'))
                        or lower(coalesce(u.email, '')) like lower(concat('%', :search, '%'))
                        or lower(coalesce(u.ownerName, '')) like lower(concat('%', :search, '%'))
                    )
                """)
    Page<User> findAllUsersForManagement(@Param("search") String search,
                                        @Param("includeDisabled") boolean includeDisabled,
                                        Pageable pageable);

    @Query("""
                select u
                from User u
                where u.tenant.id = :tenantId
                    and u.deletedAt is null
                    and (:includeDisabled = true or (coalesce(u.disabled, false) = false and lower(coalesce(u.role, '')) <> 'disabled_user'))
                    and (
                        :search is null
                        or :search = ''
                        or lower(coalesce(u.username, '')) like lower(concat('%', :search, '%'))
                        or lower(coalesce(u.email, '')) like lower(concat('%', :search, '%'))
                        or lower(coalesce(u.ownerName, '')) like lower(concat('%', :search, '%'))
                    )
                """)
    Page<User> findTenantUsersForManagement1(@Param("tenantId") int tenantId,
                                            @Param("search") String search,
                                            @Param("includeDisabled") boolean includeDisabled,
                                            Pageable pageable);


    @Query("""
    select u
    from User u
    where
        u.deletedAt is null
        and upper(coalesce(u.approvalStatus, '')) = 'PENDING_APPROVAL'
        and (
            :includeDisabled = true
            or (
                coalesce(u.disabled, false) = false
                and lower(coalesce(u.role, '')) <> 'disabled_user'
            )
        )
        and (
            :search is null
            or :search = ''
            or lower(coalesce(u.username, '')) like lower(concat('%', :search, '%'))
            or lower(coalesce(u.email, '')) like lower(concat('%', :search, '%'))
            or lower(coalesce(u.ownerName, '')) like lower(concat('%', :search, '%'))
        )
    """)
    Page<User> findTenantUsersForManagement(
            @Param("search") String search,
            @Param("includeDisabled") boolean includeDisabled,
            Pageable pageable
    );

    @Query("""
    select u
    from User u
    where u.deletedAt is null
        and (u.role is null or trim(u.role) = '' or lower(trim(u.role)) in (
            'retailer', 'store_admin', 'system_admin', 'system_administrator',
            'owner', 'admin', 'shop_owner', 'super_admin', 'org_admin'
        ))
        and (:status is null or upper(coalesce(u.approvalStatus, '')) = :status)
        and (
            :search is null
            or :search = ''
            or lower(coalesce(u.username, '')) like lower(concat('%', :search, '%'))
            or lower(coalesce(u.email, '')) like lower(concat('%', :search, '%'))
            or lower(coalesce(u.ownerName, '')) like lower(concat('%', :search, '%'))
        )
    """)
    Page<User> findRetailersForManagement(@Param("status") String status,
                                         @Param("search") String search,
                                         Pageable pageable);

    @Query("""
    select u
    from User u
    where u.deletedAt is null
        and (:approvalStatus is null or upper(coalesce(u.approvalStatus, '')) = :approvalStatus)
        and (:includeDisabled = true or (
            coalesce(u.disabled, false) = false
            and lower(coalesce(u.role, '')) <> 'disabled_user'
        ))
        and (
            :search is null
            or :search = ''
            or lower(coalesce(u.username, '')) like lower(concat('%', :search, '%'))
            or lower(coalesce(u.email, '')) like lower(concat('%', :search, '%'))
            or lower(coalesce(u.ownerName, '')) like lower(concat('%', :search, '%'))
        )
    """)
    Page<User> findUsersForManagement(
            @Param("approvalStatus") String approvalStatus,
            @Param("search") String search,
            @Param("includeDisabled") boolean includeDisabled,
            Pageable pageable
    );
}
