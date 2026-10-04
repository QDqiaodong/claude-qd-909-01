package com.reservoir.irri.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 停水窗口冲击项：窗口与某灌溉计划 / 某配水条重叠时由系统生成的「待避让」记录。
 * 调度员三选一处置：整体顺延（SHIFTED）/ 拆分到窗口外日期（SPLIT）/ 例外保留（KEPT，须填例外依据）；
 * 窗口撤销或变更后不再重叠时，尚未人工确认的项自动恢复（RESTORED）。
 * 已处理（SHIFTED / SPLIT / KEPT）的记录是历史，任何重扫与撤销都不覆盖。
 */
@Entity
@Table(name = "maintenance_impact")
@Getter
@Setter
public class MaintenanceImpact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属停水窗口 */
    @Column(name = "window_id", nullable = false)
    private Long windowId;

    /** 冲击对象类型：PLAN 灌溉计划 / ALLOT 配水条 */
    @Column(name = "target_type", nullable = false, length = 8)
    private String targetType;

    /** 冲击对象主键（irrigation_plan.id 或 allotment.id） */
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    /** 冗余渠段，便于按渠段查询 */
    @Column(name = "canal_segment_id", nullable = false)
    private Long canalSegmentId;

    /** 重叠首日（窗口覆盖到的自然日均视为停水日） */
    @Column(name = "overlap_start")
    private LocalDate overlapStart;

    /** 重叠末日 */
    @Column(name = "overlap_end")
    private LocalDate overlapEnd;

    /** 处置状态：PENDING 待避让 / SHIFTED 已顺延 / SPLIT 已拆分 / KEPT 例外保留 / RESTORED 已恢复 */
    @Column(name = "status", length = 16)
    private String status;

    /** 最终去向：处置结果说明（同步了哪些周期 / 配水 / 用水申请） */
    @Column(name = "resolution", length = 256)
    private String resolution;

    /** 例外依据：KEPT 时必填 */
    @Column(name = "exception_reason", length = 128)
    private String exceptionReason;

    /** 处置人 */
    @Column(name = "handled_by", length = 32)
    private String handledBy;

    @Column(name = "handled_at")
    private LocalDateTime handledAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    public boolean isPending() {
        return "PENDING".equals(status);
    }
}
