package com.reservoir.irri.controller;

import com.reservoir.irri.common.ApiResponse;
import com.reservoir.irri.domain.MaintenanceImpact;
import com.reservoir.irri.domain.MaintenanceWindow;
import com.reservoir.irri.dto.MaintenanceWindowRequest;
import com.reservoir.irri.dto.Views.MaintenanceWindowVO;
import com.reservoir.irri.service.MaintenanceService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 模块三补 · 渠段检修停水窗口 */
@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {

    private final MaintenanceService service;

    public MaintenanceController(MaintenanceService service) {
        this.service = service;
    }

    /** 窗口列表（含冲击项：原因与最终去向） */
    @GetMapping("/list")
    public ApiResponse<List<MaintenanceWindowVO>> list(@RequestParam(required = false) Long segmentId) {
        return ApiResponse.success(service.listVO(segmentId));
    }

    /** 登记停水窗口：生效即重扫，重叠的计划与配水条标为待避让 */
    @PostMapping
    public ApiResponse<MaintenanceWindow> create(@RequestBody MaintenanceWindowRequest request) {
        return ApiResponse.success("停水窗口已登记，重叠计划与配水已标为待避让", service.create(request));
    }

    /** 变更窗口：重扫重叠项（已人工处理的历史不覆盖） */
    @PutMapping("/{id}")
    public ApiResponse<MaintenanceWindow> update(@PathVariable Long id,
                                                 @RequestBody MaintenanceWindowRequest request) {
        return ApiResponse.success("停水窗口已变更，重叠项已重扫", service.update(id, request));
    }

    /** 撤销窗口：只恢复尚未人工确认的待避让项 */
    @PostMapping("/{id}/revoke")
    public ApiResponse<MaintenanceWindow> revoke(@PathVariable Long id,
                                                 @RequestParam(required = false) String note) {
        return ApiResponse.success("停水窗口已撤销，未确认的待避让项已恢复", service.revoke(id, note));
    }

    /** 整体顺延：计划周期 / 配水条 / 相关用水申请一起后移出窗口 */
    @PostMapping("/impact/{id}/shift")
    public ApiResponse<MaintenanceImpact> shift(@PathVariable Long id,
                                                @RequestParam(required = false) String operator) {
        return ApiResponse.success("已整体顺延，计划周期与用水申请已同步", service.shift(id, operator));
    }

    /** 拆分到窗口外日期 */
    @PostMapping("/impact/{id}/split")
    public ApiResponse<MaintenanceImpact> split(@PathVariable Long id,
                                                @RequestParam(required = false) String operator) {
        return ApiResponse.success("已拆分到窗口外日期", service.split(id, operator));
    }

    /** 例外保留：原安排不变，必须填例外依据 */
    @PostMapping("/impact/{id}/keep")
    public ApiResponse<MaintenanceImpact> keep(@PathVariable Long id, @RequestParam String reason,
                                               @RequestParam(required = false) String operator) {
        return ApiResponse.success("已例外保留", service.keep(id, reason, operator));
    }
}
