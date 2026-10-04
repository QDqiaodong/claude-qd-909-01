package com.reservoir.irri.dto;

import java.time.LocalDateTime;
import lombok.Data;

/** 停水窗口登记 / 变更入参 */
@Data
public class MaintenanceWindowRequest {

    private String windowNo;
    private Long canalSegmentId;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String reason;
    private String scope;
    private String createdBy;
    private String note;
}
