package org.ib.ibbackend.repository;

import org.ib.ibbackend.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {
}
