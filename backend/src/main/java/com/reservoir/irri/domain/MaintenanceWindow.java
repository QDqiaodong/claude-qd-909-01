package com.reservoir.irri.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 渠段检修停水窗口：运维登记的某渠段一段停水时间（起止为时刻，支持跨日）、原因与范围。
 * 生效（ACTIVE）期间，与窗口日期跨度重叠的灌溉计划 / 配水条会被系统标为「待避让」；
 * 撤销（REVOKED）时只恢复尚未人工确认的待避让项，已处理结果与历史不覆盖。
 */
@Entity
@Table(name = "maintenance_window")
@Getter
@Setter
public class MaintenanceWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 窗口编号：全灌区唯一，如 MW-2026-002 */
    @Column(name = "window_no", nullable = false, unique = true, length = 32)
    private String windowNo;

    /** 停水渠段 */
    @Column(name = "canal_segment_id", nullable = false)
    private Long canalSegmentId;

    /** 停水开始时间（可跨日） */
    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    /** 停水结束时间：必须晚于开始时间 */
    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    /** 停水原因 */
    @Column(name = "reason", length = 128)
    private String reason;

    /** 影响范围，如 全段停水 / 仅下游 2+000~4+000 */
    @Column(name = "scope", length = 128)
    private String scope;

    /** 窗口状态：ACTIVE 生效中 / REVOKED 已撤销 */
    @Column(name = "status", length = 16)
    private String status;

    /** 登记人（运维） */
    @Column(name = "created_by", length = 32)
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoke_note", length = 128)
    private String revokeNote;

    @Column(name = "note", length = 128)
    private String note;

    @Transient
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
