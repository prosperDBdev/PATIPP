package com.patipp.preparations.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PreparationTypeRepository extends JpaRepository<PreparationType, UUID> {

    Optional<PreparationType> findByKey(String key);

    /** System types plus the caller's own. One user's custom type is invisible to another. */
    @Query("""
            select t from PreparationType t
            where t.system = true or t.createdBy = :userId
            order by t.system desc, t.name asc
            """)
    List<PreparationType> findAvailableTo(@Param("userId") UUID userId);

    @Query("""
            select t from PreparationType t
            where t.id = :id and (t.system = true or t.createdBy = :userId)
            """)
    Optional<PreparationType> findUsableBy(@Param("id") UUID id, @Param("userId") UUID userId);
}
