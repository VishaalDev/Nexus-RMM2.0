package com.nexusrmm.repository;

import com.nexusrmm.entity.HardwareEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface HardwareEventRepository extends JpaRepository<HardwareEvent, Long> {

    boolean existsByComputerIdAndProviderNameAndRecordId(String computerId, String providerName, Long recordId);

    @Query("""
        SELECT e FROM HardwareEvent e
        WHERE e.computerId = :computerId
        ORDER BY e.occurredAt DESC
    """)
    List<HardwareEvent> findRecent(@Param("computerId") String computerId, Pageable pageable);

    long countByComputerId(String computerId);

    /** Module 2: events at or after {@code since}, newest first; bound the result with the Pageable. */
    @Query("""
        SELECT e FROM HardwareEvent e
        WHERE e.computerId = :computerId
          AND e.occurredAt >= :since
        ORDER BY e.occurredAt DESC
    """)
    List<HardwareEvent> findSince(@Param("computerId") String computerId,
                                  @Param("since") Instant since,
                                  Pageable pageable);
}
