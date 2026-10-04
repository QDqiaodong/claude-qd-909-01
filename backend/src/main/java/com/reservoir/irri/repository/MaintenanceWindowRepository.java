package com.reservoir.irri.repository;

import com.reservoir.irri.domain.MaintenanceWindow;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaintenanceWindowRepository extends JpaRepository<MaintenanceWindow, Long> {

    boolean existsByWindowNo(String windowNo);

    List<MaintenanceWindow> findAllByOrderByIdDesc();

    List<MaintenanceWindow> findByCanalSegmentIdOrderByIdDesc(Long canalSegmentId);

    List<MaintenanceWindow> findByCanalSegmentIdAndStatus(Long canalSegmentId, String status);

    /** 与给定时刻区间有重叠的生效中窗口（甘特图着色用） */
    @Query("select w from MaintenanceWindow w where w.status = 'ACTIVE' "
            + "and w.startAt <= :rangeEnd and w.endAt >= :rangeStart order by w.id asc")
    List<MaintenanceWindow> findActiveBetween(@Param("rangeStart") LocalDateTime rangeStart,
                                              @Param("rangeEnd") LocalDateTime rangeEnd);

    @Query("select count(w) from MaintenanceWindow w where w.status = :status")
    long countByStatus(@Param("status") String status);
}
