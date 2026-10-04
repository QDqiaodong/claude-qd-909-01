package com.reservoir.irri.repository;

import com.reservoir.irri.domain.MaintenanceImpact;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaintenanceImpactRepository extends JpaRepository<MaintenanceImpact, Long> {

    List<MaintenanceImpact> findByWindowIdOrderByIdAsc(Long windowId);

    List<MaintenanceImpact> findByWindowIdAndTargetTypeAndTargetId(Long windowId, String targetType, Long targetId);

    @Query("select count(i) from MaintenanceImpact i where i.status = :status")
    long countByStatus(@Param("status") String status);
}
