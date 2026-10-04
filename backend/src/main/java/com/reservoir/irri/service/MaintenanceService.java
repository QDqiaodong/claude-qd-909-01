package com.reservoir.irri.service;

import com.reservoir.irri.common.BizException;
import com.reservoir.irri.common.Dict;
import com.reservoir.irri.domain.Allotment;
import com.reservoir.irri.domain.CanalSegment;
import com.reservoir.irri.domain.IrrigationPlan;
import com.reservoir.irri.domain.MaintenanceImpact;
import com.reservoir.irri.domain.MaintenanceWindow;
import com.reservoir.irri.domain.WaterRequest;
import com.reservoir.irri.dto.MaintenanceWindowRequest;
import com.reservoir.irri.dto.Views.MaintenanceImpactVO;
import com.reservoir.irri.dto.Views.MaintenanceWindowVO;
import com.reservoir.irri.repository.AllotmentRepository;
import com.reservoir.irri.repository.CanalSegmentRepository;
import com.reservoir.irri.repository.IrrigationPlanRepository;
import com.reservoir.irri.repository.MaintenanceImpactRepository;
import com.reservoir.irri.repository.MaintenanceWindowRepository;
import com.reservoir.irri.repository.WaterRequestRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 模块三补 · 渠段检修停水窗口。
 *
 * <p>规则（全部落在 service，抛 BizException）：
 * <ol>
 *   <li>窗口编号唯一；停水结束时间必须晚于开始时间（起止为时刻，支持跨日；
 *       窗口覆盖到的自然日均视为停水日）</li>
 *   <li>窗口生效 / 变更时重扫：与窗口日期跨度重叠的未结束计划、未取消未完成的配水条 → 待避让；
 *       已人工处理（已顺延 / 已拆分 / 例外保留）的记录是历史，重扫不覆盖；
 *       仍待避让但已不重叠的项自动恢复</li>
 *   <li>调度员三选一：整体顺延 / 拆分到窗口外日期 / 例外保留（须填例外依据）；
 *       顺延与拆分同步计划周期、剩余可配水量（由配水条移动自然重算）与相关用水申请的供水日期</li>
 *   <li>撤销窗口只恢复尚未人工确认的待避让项，已调整或例外保留的结果与历史不被覆盖</li>
 *   <li>每次处置后重扫该渠段全部生效窗口，发现「调整后再次冲突」并重新标待避让</li>
 * </ol>
 */
@Service
public class MaintenanceService {

    private final MaintenanceWindowRepository windowRepository;
    private final MaintenanceImpactRepository impactRepository;
    private final IrrigationPlanRepository planRepository;
    private final AllotmentRepository allotmentRepository;
    private final WaterRequestRepository requestRepository;
    private final CanalSegmentRepository segmentRepository;

    public MaintenanceService(MaintenanceWindowRepository windowRepository,
                              MaintenanceImpactRepository impactRepository,
                              IrrigationPlanRepository planRepository,
                              AllotmentRepository allotmentRepository,
                              WaterRequestRepository requestRepository,
                              CanalSegmentRepository segmentRepository) {
        this.windowRepository = windowRepository;
        this.impactRepository = impactRepository;
        this.planRepository = planRepository;
        this.allotmentRepository = allotmentRepository;
        this.requestRepository = requestRepository;
        this.segmentRepository = segmentRepository;
    }

    // ---------------- 查询 ----------------

    public List<MaintenanceWindow> list(Long segmentId) {
        if (segmentId == null) {
            return windowRepository.findAllByOrderByIdDesc();
        }
        return windowRepository.findByCanalSegmentIdOrderByIdDesc(segmentId);
    }

    /** 窗口 + 它的冲击项（含对象简述与最终去向） */
    public List<MaintenanceWindowVO> listVO(Long segmentId) {
        Map<Long, CanalSegment> segments = segmentRepository.findAll().stream()
                .collect(Collectors.toMap(CanalSegment::getId, s -> s, (a, b) -> a));
        Map<Long, IrrigationPlan> plans = planRepository.findAll().stream()
                .collect(Collectors.toMap(IrrigationPlan::getId, p -> p, (a, b) -> a));
        Map<Long, Allotment> allotments = allotmentRepository.findAll().stream()
                .collect(Collectors.toMap(Allotment::getId, a -> a, (a, b) -> a));
        List<MaintenanceWindowVO> result = new ArrayList<>();
        for (MaintenanceWindow window : list(segmentId)) {
            List<MaintenanceImpact> impacts = impactRepository.findByWindowIdOrderByIdAsc(window.getId());
            result.add(toWindowVO(window, segments.get(window.getCanalSegmentId()), impacts, plans, allotments));
        }
        return result;
    }

    public MaintenanceWindow getWindow(Long id) {
        return windowRepository.findById(id).orElseThrow(() -> new BizException("停水窗口不存在：" + id));
    }

    public MaintenanceImpact getImpact(Long id) {
        return impactRepository.findById(id).orElseThrow(() -> new BizException("待避让记录不存在：" + id));
    }

    // ---------------- 登记与变更 ----------------

    @Transactional
    public MaintenanceWindow create(MaintenanceWindowRequest request) {
        if (!StringUtils.hasText(request.getWindowNo())) {
            throw new BizException("窗口编号不能为空");
        }
        if (windowRepository.existsByWindowNo(request.getWindowNo())) {
            throw new BizException("窗口编号已存在：" + request.getWindowNo());
        }
        if (request.getCanalSegmentId() == null) {
            throw new BizException("请选择停水渠段");
        }
        segmentRepository.findById(request.getCanalSegmentId())
                .orElseThrow(() -> new BizException("渠段不存在：" + request.getCanalSegmentId()));
        assertPeriod(request.getStartAt(), request.getEndAt());
        if (!StringUtils.hasText(request.getReason())) {
            throw new BizException("请填写停水原因");
        }

        MaintenanceWindow window = new MaintenanceWindow();
        window.setWindowNo(request.getWindowNo());
        window.setCanalSegmentId(request.getCanalSegmentId());
        window.setStartAt(request.getStartAt());
        window.setEndAt(request.getEndAt());
        window.setReason(request.getReason());
        window.setScope(StringUtils.hasText(request.getScope()) ? request.getScope() : "全段停水");
        window.setNote(request.getNote());
        // 默认值写在 create 里
        window.setStatus("ACTIVE");
        window.setCreatedBy(StringUtils.hasText(request.getCreatedBy()) ? request.getCreatedBy() : "渠道运维");
        window.setCreatedAt(LocalDateTime.now());
        MaintenanceWindow saved = windowRepository.save(window);
        // 窗口生效：找出重叠的灌溉计划与配水条并标为待避让
        detect(saved);
        return saved;
    }

    @Transactional
    public MaintenanceWindow update(Long id, MaintenanceWindowRequest request) {
        MaintenanceWindow window = getWindow(id);
        if (!window.isActive()) {
            throw new BizException("窗口已撤销，属于历史记录，不允许再变更");
        }
        if (StringUtils.hasText(request.getWindowNo())
                && !request.getWindowNo().equals(window.getWindowNo())
                && windowRepository.existsByWindowNo(request.getWindowNo())) {
            throw new BizException("窗口编号已存在：" + request.getWindowNo());
        }
        LocalDateTime start = request.getStartAt() != null ? request.getStartAt() : window.getStartAt();
        LocalDateTime end = request.getEndAt() != null ? request.getEndAt() : window.getEndAt();
        assertPeriod(start, end);
        if (request.getCanalSegmentId() != null && !request.getCanalSegmentId().equals(window.getCanalSegmentId())) {
            segmentRepository.findById(request.getCanalSegmentId())
                    .orElseThrow(() -> new BizException("渠段不存在：" + request.getCanalSegmentId()));
            window.setCanalSegmentId(request.getCanalSegmentId());
        }
        CanalService.ifNotNull(request.getWindowNo(), window::setWindowNo);
        CanalService.ifNotNull(request.getStartAt(), window::setStartAt);
        CanalService.ifNotNull(request.getEndAt(), window::setEndAt);
        CanalService.ifNotNull(request.getReason(), window::setReason);
        CanalService.ifNotNull(request.getScope(), window::setScope);
        CanalService.ifNotNull(request.getNote(), window::setNote);
        MaintenanceWindow saved = windowRepository.save(window);
        // 窗口变更：重扫重叠项（新重叠标待避让，不再重叠的待避让自动恢复，已处理的不动）
        detect(saved);
        return saved;
    }

    /**
     * 撤销窗口：只恢复尚未人工确认的待避让项；
     * 已顺延 / 已拆分 / 例外保留的结果与历史不被覆盖。
     */
    @Transactional
    public MaintenanceWindow revoke(Long id, String note) {
        MaintenanceWindow window = getWindow(id);
        if (!window.isActive()) {
            throw new BizException("窗口已撤销，不能重复撤销");
        }
        window.setStatus("REVOKED");
        window.setRevokedAt(LocalDateTime.now());
        window.setRevokeNote(note);
        for (MaintenanceImpact impact : impactRepository.findByWindowIdOrderByIdAsc(id)) {
            if (!impact.isPending()) {
                continue; // 已人工处理：历史不覆盖
            }
            impact.setStatus("RESTORED");
            impact.setResolution("窗口撤销，待避让标记已恢复");
            impact.setHandledBy("系统");
            impact.setHandledAt(LocalDateTime.now());
            impactRepository.save(impact);
        }
        return windowRepository.save(window);
    }

    // ---------------- 避让处置 ----------------

    /** 整体顺延：计划整周期后移出窗口 / 配水条移到窗口结束次日 */
    @Transactional
    public MaintenanceImpact shift(Long impactId, String operator) {
        MaintenanceImpact impact = getImpact(impactId);
        assertPending(impact);
        MaintenanceWindow window = getWindow(impact.getWindowId());
        assertWindowActive(window);
        String detail;
        if ("PLAN".equals(impact.getTargetType())) {
            detail = shiftPlan(impact, window);
        } else {
            detail = shiftAllotment(impact, window);
        }
        impact.setStatus("SHIFTED");
        impact.setResolution(clip(detail));
        impact.setHandledBy(operator == null ? "灌区调度" : operator);
        impact.setHandledAt(LocalDateTime.now());
        MaintenanceImpact saved = impactRepository.save(impact);
        // 调整后再次冲突检测
        rescanSegment(window.getCanalSegmentId());
        return saved;
    }

    /** 拆分到窗口外日期：窗内配水按当日剩余容量向窗口结束日之后逐日拆分 */
    @Transactional
    public MaintenanceImpact split(Long impactId, String operator) {
        MaintenanceImpact impact = getImpact(impactId);
        assertPending(impact);
        MaintenanceWindow window = getWindow(impact.getWindowId());
        assertWindowActive(window);
        String detail;
        if ("PLAN".equals(impact.getTargetType())) {
            detail = splitPlan(impact, window);
        } else {
            detail = splitAllotment(impact, window);
        }
        impact.setStatus("SPLIT");
        impact.setResolution(clip(detail));
        impact.setHandledBy(operator == null ? "灌区调度" : operator);
        impact.setHandledAt(LocalDateTime.now());
        MaintenanceImpact saved = impactRepository.save(impact);
        // 调整后再次冲突检测
        rescanSegment(window.getCanalSegmentId());
        return saved;
    }

    /** 例外保留：原安排不变，必须填例外依据 */
    @Transactional
    public MaintenanceImpact keep(Long impactId, String reason, String operator) {
        MaintenanceImpact impact = getImpact(impactId);
        assertPending(impact);
        assertWindowActive(getWindow(impact.getWindowId()));
        if (!StringUtils.hasText(reason)) {
            throw new BizException("例外保留必须填写例外依据");
        }
        impact.setStatus("KEPT");
        impact.setExceptionReason(clip(reason, 128));
        impact.setResolution(clip("例外保留原安排：" + reason));
        impact.setHandledBy(operator == null ? "灌区调度" : operator);
        impact.setHandledAt(LocalDateTime.now());
        return impactRepository.save(impact);
    }

    // ---------------- 冲突检测 ----------------

    /**
     * 重扫某渠段全部生效窗口：新重叠标待避让、不再重叠的待避让自动恢复。
     * 供计划模块在人工调整配水 / 计划后调用，捕捉「调整后再次冲突」。
     */
    @Transactional
    public void rescanSegment(Long segmentId) {
        if (segmentId == null) {
            return;
        }
        for (MaintenanceWindow window : windowRepository.findByCanalSegmentIdAndStatus(segmentId, "ACTIVE")) {
            detect(window);
        }
    }

    /** 窗口生效 / 变更时的冲突检测：重叠 → 待避让；待避让但不再重叠 → 自动恢复 */
    private void detect(MaintenanceWindow window) {
        LocalDate from = window.getStartAt().toLocalDate();
        LocalDate to = window.getEndAt().toLocalDate();
        // 计划：周期与窗口日期跨度相交且未结束
        for (IrrigationPlan plan : planRepository.findOverlap(window.getCanalSegmentId(), from, to)) {
            if ("FINISHED".equals(plan.getStatus())) {
                continue;
            }
            LocalDate overlapStart = plan.getStartDate().isAfter(from) ? plan.getStartDate() : from;
            LocalDate overlapEnd = plan.getEndDate().isBefore(to) ? plan.getEndDate() : to;
            upsertImpact(window, "PLAN", plan.getId(), overlapStart, overlapEnd);
        }
        // 配水条：落在窗口日期跨度内且未取消、未完成
        for (Allotment allotment : allotmentRepository.findBetween(from, to)) {
            if (!window.getCanalSegmentId().equals(allotment.getCanalSegmentId())) {
                continue;
            }
            if (allotment.isCanceled() || "DONE".equals(allotment.getStatus())) {
                continue;
            }
            upsertImpact(window, "ALLOT", allotment.getId(), allotment.getAllotDate(), allotment.getAllotDate());
        }
        // 该窗口下仍待避让但已不重叠的 → 自动恢复（窗口变更 / 人工调整后）
        for (MaintenanceImpact impact : impactRepository.findByWindowIdOrderByIdAsc(window.getId())) {
            if (!impact.isPending()) {
                continue;
            }
            if (!stillOverlapping(window, impact)) {
                impact.setStatus("RESTORED");
                impact.setResolution("目标已移出窗口或窗口已变更，待避让标记自动恢复");
                impact.setHandledBy("系统");
                impact.setHandledAt(LocalDateTime.now());
                impactRepository.save(impact);
            }
        }
    }

    /** 已人工处理（已顺延 / 已拆分 / 例外保留）的记录是历史，不覆盖；待避让的更新重叠区间 */
    private void upsertImpact(MaintenanceWindow window, String targetType, Long targetId,
                              LocalDate overlapStart, LocalDate overlapEnd) {
        List<MaintenanceImpact> existing =
                impactRepository.findByWindowIdAndTargetTypeAndTargetId(window.getId(), targetType, targetId);
        for (MaintenanceImpact impact : existing) {
            if (impact.isPending()) {
                impact.setOverlapStart(overlapStart);
                impact.setOverlapEnd(overlapEnd);
                impactRepository.save(impact);
                return;
            }
            // SHIFTED / SPLIT / KEPT：处置结果不覆盖；RESTORED：历史不覆盖，落下去新建
            if (!"RESTORED".equals(impact.getStatus())) {
                return;
            }
        }
        MaintenanceImpact impact = new MaintenanceImpact();
        impact.setWindowId(window.getId());
        impact.setTargetType(targetType);
        impact.setTargetId(targetId);
        impact.setCanalSegmentId(window.getCanalSegmentId());
        impact.setOverlapStart(overlapStart);
        impact.setOverlapEnd(overlapEnd);
        impact.setStatus("PENDING");
        impact.setCreatedAt(LocalDateTime.now());
        impactRepository.save(impact);
    }

    /** 待避让项当前是否仍与窗口重叠（窗口时间 / 渠段变更、目标调整都会让它失效） */
    private boolean stillOverlapping(MaintenanceWindow window, MaintenanceImpact impact) {
        LocalDate from = window.getStartAt().toLocalDate();
        LocalDate to = window.getEndAt().toLocalDate();
        if ("PLAN".equals(impact.getTargetType())) {
            IrrigationPlan plan = planRepository.findById(impact.getTargetId()).orElse(null);
            if (plan == null || "FINISHED".equals(plan.getStatus())
                    || !window.getCanalSegmentId().equals(plan.getCanalSegmentId())) {
                return false;
            }
            return plan.getStartDate() != null && plan.getEndDate() != null
                    && !plan.getEndDate().isBefore(from) && !plan.getStartDate().isAfter(to);
        }
        Allotment allotment = allotmentRepository.findById(impact.getTargetId()).orElse(null);
        if (allotment == null || allotment.isCanceled() || "DONE".equals(allotment.getStatus())
                || allotment.getAllotDate() == null
                || !window.getCanalSegmentId().equals(allotment.getCanalSegmentId())) {
            return false;
        }
        return !allotment.getAllotDate().isBefore(from) && !allotment.getAllotDate().isAfter(to);
    }

    // ---------------- 整体顺延 ----------------

    /** 计划整体顺延：周期、未配完的配水条、相关用水申请一起后移出窗口 */
    private String shiftPlan(MaintenanceImpact impact, MaintenanceWindow window) {
        IrrigationPlan plan = planRepository.findById(impact.getTargetId())
                .orElseThrow(() -> new BizException("灌溉计划不存在：" + impact.getTargetId()));
        if ("FINISHED".equals(plan.getStatus())) {
            throw new BizException("计划已结束，无需顺延");
        }
        LocalDate windowEnd = window.getEndAt().toLocalDate();
        long shiftDays = ChronoUnit.DAYS.between(plan.getStartDate(), windowEnd) + 1;
        if (shiftDays <= 0) {
            throw new BizException("计划周期已不在窗口内，无需顺延");
        }
        // 未取消、未完成的配水条整体后移；目标日容量校验（排除这批一起搬的配水）
        List<Allotment> moving = new ArrayList<>();
        for (Allotment allotment : allotmentRepository.findByPlanIdOrderByAllotDateAsc(plan.getId())) {
            if (!allotment.isCanceled() && !"DONE".equals(allotment.getStatus())) {
                moving.add(allotment);
            }
        }
        Set<Long> movingIds = moving.stream().map(Allotment::getId).collect(Collectors.toSet());
        for (Allotment allotment : moving) {
            assertDayCapacity(allotment.getCanalSegmentId(),
                    allotment.getAllotDate().plusDays(shiftDays), allotment.getFlow(), movingIds);
        }
        LocalDate oldStart = plan.getStartDate();
        LocalDate oldEnd = plan.getEndDate();
        plan.setStartDate(oldStart.plusDays(shiftDays));
        plan.setEndDate(oldEnd.plusDays(shiftDays));
        recalcDays(plan);
        recalcVolume(plan);
        planRepository.save(plan);
        for (Allotment allotment : moving) {
            allotment.setAllotDate(allotment.getAllotDate().plusDays(shiftDays));
            allotmentRepository.save(allotment);
        }
        int synced = shiftRequests(plan.getCanalSegmentId(), oldStart, oldEnd, shiftDays);
        return "整体顺延 " + shiftDays + " 天：计划周期 " + oldStart + "~" + oldEnd + " → "
                + plan.getStartDate() + "~" + plan.getEndDate()
                + "；同步移动配水 " + moving.size() + " 条、用水申请 " + synced + " 份，剩余可配水量按新日期重算";
    }

    /** 单条配水顺延到窗口结束次日；超出计划结束日时同步顺延计划周期 */
    private String shiftAllotment(MaintenanceImpact impact, MaintenanceWindow window) {
        Allotment allotment = getAllotment(impact.getTargetId());
        assertAllotmentActive(allotment);
        LocalDate target = window.getEndAt().toLocalDate().plusDays(1);
        assertDayCapacity(allotment.getCanalSegmentId(), target, allotment.getFlow(), Set.of(allotment.getId()));
        LocalDate oldDate = allotment.getAllotDate();
        allotment.setAllotDate(target);
        allotmentRepository.save(allotment);
        StringBuilder detail = new StringBuilder("配水 " + oldDate + " → " + target);
        IrrigationPlan plan = planRepository.findById(allotment.getPlanId()).orElse(null);
        if (plan != null && plan.getEndDate() != null && target.isAfter(plan.getEndDate())) {
            plan.setEndDate(target);
            recalcDays(plan);
            recalcVolume(plan);
            planRepository.save(plan);
            detail.append("；计划周期顺延至 ").append(target);
        }
        int synced = shiftRequests(allotment.getCanalSegmentId(), oldDate, oldDate,
                ChronoUnit.DAYS.between(oldDate, target));
        detail.append("；同步用水申请 ").append(synced).append(" 份，剩余可配水量按新日期重算");
        return detail.toString();
    }

    // ---------------- 拆分到窗口外 ----------------

    /** 计划拆分：把落在窗口内的配水条逐条拆到窗口外日期 */
    private String splitPlan(MaintenanceImpact impact, MaintenanceWindow window) {
        IrrigationPlan plan = planRepository.findById(impact.getTargetId())
                .orElseThrow(() -> new BizException("灌溉计划不存在：" + impact.getTargetId()));
        LocalDate from = window.getStartAt().toLocalDate();
        LocalDate to = window.getEndAt().toLocalDate();
        List<Allotment> inside = new ArrayList<>();
        for (Allotment allotment : allotmentRepository.findByPlanIdOrderByAllotDateAsc(plan.getId())) {
            if (allotment.isCanceled() || "DONE".equals(allotment.getStatus()) || allotment.getAllotDate() == null) {
                continue;
            }
            if (!allotment.getAllotDate().isBefore(from) && !allotment.getAllotDate().isAfter(to)) {
                inside.add(allotment);
            }
        }
        if (inside.isEmpty()) {
            throw new BizException("计划周期内没有落在窗口中的配水条，无需拆分（可改选整体顺延或例外保留）");
        }
        List<String> parts = new ArrayList<>();
        int synced = 0;
        for (Allotment allotment : inside) {
            SplitResult result = splitOneAllotment(allotment, to);
            parts.add(result.detail);
            synced += result.syncedRequests;
        }
        return "窗内 " + inside.size() + " 条配水拆分到窗口外：" + String.join("；", parts)
                + "；同步用水申请 " + synced + " 份";
    }

    /** 单条配水拆分到窗口外日期 */
    private String splitAllotment(MaintenanceImpact impact, MaintenanceWindow window) {
        Allotment allotment = getAllotment(impact.getTargetId());
        assertAllotmentActive(allotment);
        SplitResult result = splitOneAllotment(allotment, window.getEndAt().toLocalDate());
        return result.detail + "；同步用水申请 " + result.syncedRequests + " 份";
    }

    /**
     * 把一条配水的流量按「窗口结束次日 → 之后」逐日剩余容量拆分：
     * 原配水条取消（保留历史），新配水条继承计划与时长；总量不变，剩余可配水量按新日期重算。
     */
    private SplitResult splitOneAllotment(Allotment allotment, LocalDate windowEnd) {
        CanalSegment segment = segmentRepository.findById(allotment.getCanalSegmentId())
                .orElseThrow(() -> new BizException("渠段不存在：" + allotment.getCanalSegmentId()));
        BigDecimal design = CanalService.nvl(segment.getDesignFlow());
        if (design.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("渠段 " + segment.getSegmentCode() + " 未设置设计流量，无法拆分");
        }
        BigDecimal flowLeft = CanalService.nvl(allotment.getFlow());
        int hours = allotment.getHours() == null ? 24 : allotment.getHours();
        LocalDate oldDate = allotment.getAllotDate();
        LocalDate day = windowEnd.plusDays(1);
        LocalDate firstDay = null;
        LocalDate lastDay = null;
        List<String> pieces = new ArrayList<>();
        int guard = 0;
        while (flowLeft.compareTo(BigDecimal.ZERO) > 0) {
            if (++guard > Dict.MAX_SPLIT_SEARCH_DAYS) {
                throw new BizException("窗口外 " + Dict.MAX_SPLIT_SEARCH_DAYS + " 天内可用容量不足，无法完整拆分 "
                        + oldDate + " 的配水，请改选整体顺延或例外保留");
            }
            BigDecimal free = design.subtract(usedFlow(allotment.getCanalSegmentId(), day, Set.of(allotment.getId())));
            if (free.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal flow = free.min(flowLeft);
                Allotment piece = new Allotment();
                piece.setPlanId(allotment.getPlanId());
                piece.setCanalSegmentId(allotment.getCanalSegmentId());
                piece.setAllotDate(day);
                piece.setFlow(flow);
                piece.setHours(hours);
                piece.setStatus("PLANNED");
                piece.setNote("停水避让拆分自 #" + allotment.getId() + "（" + oldDate + "）");
                piece.setVolumeWan(flow.multiply(BigDecimal.valueOf(hours))
                        .multiply(Dict.HOUR_FLOW_TO_VOLUME).setScale(2, RoundingMode.HALF_UP));
                allotmentRepository.save(piece);
                pieces.add(day + "（" + flow.stripTrailingZeros().toPlainString() + "m³/s）");
                flowLeft = flowLeft.subtract(flow);
                if (firstDay == null) {
                    firstDay = day;
                }
                lastDay = day;
            }
            day = day.plusDays(1);
        }
        // 原配水条取消（保留历史，不物理删除）
        allotment.setStatus("CANCELED");
        allotment.setNote(appendNote(allotment.getNote(), "停水窗口拆分，原条取消"));
        allotmentRepository.save(allotment);
        // 同步计划周期：拆分落日晚于原结束日时顺延
        IrrigationPlan plan = planRepository.findById(allotment.getPlanId()).orElse(null);
        if (plan != null && lastDay != null && plan.getEndDate() != null && lastDay.isAfter(plan.getEndDate())) {
            plan.setEndDate(lastDay);
            recalcDays(plan);
            recalcVolume(plan);
            planRepository.save(plan);
        }
        // 同步相关用水申请的供水日期 → 首个拆分日
        int synced = firstDay == null ? 0
                : shiftRequests(allotment.getCanalSegmentId(), oldDate, oldDate,
                        ChronoUnit.DAYS.between(oldDate, firstDay));
        SplitResult result = new SplitResult();
        result.detail = oldDate + " 配水 " + allotment.getFlow() + "m³/s 拆到 "
                + String.join("、", pieces) + "，原配水条已取消";
        result.syncedRequests = synced;
        return result;
    }

    // ---------------- 同步与校验 ----------------

    /** 同步相关用水申请的供水日期：同渠段、落在 [from,to] 内的待审 / 已批申请整体平移（终态不动） */
    private int shiftRequests(Long segmentId, LocalDate from, LocalDate to, long days) {
        if (days == 0 || from == null || to == null) {
            return 0;
        }
        List<WaterRequest> list = requestRepository.findActiveBetween(segmentId, from, to);
        for (WaterRequest req : list) {
            req.setAllotDate(req.getAllotDate().plusDays(days));
            requestRepository.save(req);
        }
        return list.size();
    }

    /** 该渠段该日已配流量合计（排除指定配水条） */
    private BigDecimal usedFlow(Long segmentId, LocalDate day, Set<Long> excludeIds) {
        BigDecimal used = BigDecimal.ZERO;
        for (Allotment allotment : allotmentRepository.findActiveBySegmentAndDate(segmentId, day)) {
            if (excludeIds != null && excludeIds.contains(allotment.getId())) {
                continue;
            }
            used = used.add(CanalService.nvl(allotment.getFlow()));
        }
        return used;
    }

    /** 顺延 / 拆分的落点日容量校验：与配水模块同一口径（合计流量不得超过设计流量） */
    private void assertDayCapacity(Long segmentId, LocalDate day, BigDecimal flow, Set<Long> excludeIds) {
        CanalSegment segment = segmentRepository.findById(segmentId)
                .orElseThrow(() -> new BizException("渠段不存在：" + segmentId));
        BigDecimal design = CanalService.nvl(segment.getDesignFlow());
        BigDecimal used = usedFlow(segmentId, day, excludeIds);
        if (used.add(CanalService.nvl(flow)).compareTo(design) > 0) {
            throw new BizException("渠段 " + segment.getSegmentCode() + " " + day + " 可用容量不足（设计 "
                    + design + " m³/s，已排 " + used.setScale(2, RoundingMode.HALF_UP) + " m³/s，还需再放 "
                    + flow + " m³/s），请改选拆分或例外保留");
        }
    }

    private void assertPending(MaintenanceImpact impact) {
        if (!impact.isPending()) {
            throw new BizException("该冲击项已" + Dict.impactStatusName(impact.getStatus()) + "，不允许重复处置");
        }
    }

    private void assertWindowActive(MaintenanceWindow window) {
        if (!window.isActive()) {
            throw new BizException("窗口已撤销，待避让项已自动恢复，无需处置");
        }
    }

    private void assertAllotmentActive(Allotment allotment) {
        if (allotment.isCanceled() || "DONE".equals(allotment.getStatus())) {
            throw new BizException("配水条已" + Dict.allotStatusName(allotment.getStatus()) + "，无需避让");
        }
    }

    /** 规则：停水结束时间必须晚于开始时间 */
    private void assertPeriod(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            throw new BizException("请填写停水起止时间");
        }
        if (!end.isAfter(start)) {
            throw new BizException("停水结束时间 " + end + " 必须晚于开始时间 " + start);
        }
    }

    private Allotment getAllotment(Long id) {
        return allotmentRepository.findById(id).orElseThrow(() -> new BizException("配水记录不存在：" + id));
    }

    /** 计划天数 = 结束日 − 开始日 + 1 */
    private void recalcDays(IrrigationPlan plan) {
        if (plan.getStartDate() != null && plan.getEndDate() != null) {
            long days = ChronoUnit.DAYS.between(plan.getStartDate(), plan.getEndDate()) + 1;
            plan.setPlanDays((int) Math.max(0, days));
        }
    }

    /** 计划配水总量（万m³）= 计划流量 × 天数 × 8.64 */
    private void recalcVolume(IrrigationPlan plan) {
        if (plan.getPlanFlow() != null && plan.getPlanDays() != null && plan.getPlanDays() > 0) {
            plan.setPlanVolume(plan.getPlanFlow()
                    .multiply(BigDecimal.valueOf(plan.getPlanDays()))
                    .multiply(Dict.DAY_FLOW_TO_VOLUME)
                    .setScale(2, RoundingMode.HALF_UP));
        }
    }

    private static String appendNote(String note, String extra) {
        return StringUtils.hasText(note) ? note + "；" + extra : extra;
    }

    /** resolution / exception_reason 列长有限，系统生成的去向说明超长时截断 */
    private static String clip(String text) {
        return clip(text, 250);
    }

    private static String clip(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…";
    }

    /** 拆分结果：去向说明 + 同步的用水申请份数 */
    private static final class SplitResult {
        private String detail;
        private int syncedRequests;
    }

    // ---------------- VO ----------------

    public static MaintenanceWindowVO toWindowVO(MaintenanceWindow window, CanalSegment segment,
                                                 List<MaintenanceImpact> impacts,
                                                 Map<Long, IrrigationPlan> plans,
                                                 Map<Long, Allotment> allotments) {
        MaintenanceWindowVO vo = new MaintenanceWindowVO();
        vo.setId(window.getId());
        vo.setWindowNo(window.getWindowNo());
        vo.setCanalSegmentId(window.getCanalSegmentId());
        vo.setSegmentCode(segment == null ? null : segment.getSegmentCode());
        vo.setSegmentName(segment == null ? null : segment.getSegmentName());
        vo.setStartAt(CanalService.str(window.getStartAt()));
        vo.setEndAt(CanalService.str(window.getEndAt()));
        vo.setStartDate(window.getStartAt() == null ? null : window.getStartAt().toLocalDate().toString());
        vo.setEndDate(window.getEndAt() == null ? null : window.getEndAt().toLocalDate().toString());
        vo.setReason(window.getReason());
        vo.setScope(window.getScope());
        vo.setStatus(window.getStatus());
        vo.setStatusName(Dict.mwStatusName(window.getStatus()));
        vo.setStatusColor(Dict.mwStatusColor(window.getStatus()));
        vo.setCreatedBy(window.getCreatedBy());
        vo.setCreatedAt(CanalService.str(window.getCreatedAt()));
        vo.setRevokedAt(CanalService.str(window.getRevokedAt()));
        vo.setRevokeNote(window.getRevokeNote());
        vo.setNote(window.getNote());
        if (impacts != null) {
            vo.setImpactCount(impacts.size());
            vo.setPendingCount((int) impacts.stream().filter(MaintenanceImpact::isPending).count());
            for (MaintenanceImpact impact : impacts) {
                vo.getImpacts().add(toImpactVO(impact, window, plans, allotments));
            }
        }
        return vo;
    }

    public static MaintenanceImpactVO toImpactVO(MaintenanceImpact impact, MaintenanceWindow window,
                                                 Map<Long, IrrigationPlan> plans,
                                                 Map<Long, Allotment> allotments) {
        MaintenanceImpactVO vo = new MaintenanceImpactVO();
        vo.setId(impact.getId());
        vo.setWindowId(impact.getWindowId());
        vo.setWindowNo(window == null ? null : window.getWindowNo());
        vo.setWindowReason(window == null ? null : window.getReason());
        vo.setTargetType(impact.getTargetType());
        vo.setTargetTypeName(Dict.impactTargetName(impact.getTargetType()));
        vo.setTargetId(impact.getTargetId());
        vo.setCanalSegmentId(impact.getCanalSegmentId());
        vo.setTargetLabel(targetLabel(impact, plans, allotments));
        vo.setOverlapStart(CanalService.str(impact.getOverlapStart()));
        vo.setOverlapEnd(CanalService.str(impact.getOverlapEnd()));
        vo.setStatus(impact.getStatus());
        vo.setStatusName(Dict.impactStatusName(impact.getStatus()));
        vo.setStatusColor(Dict.impactStatusColor(impact.getStatus()));
        vo.setResolution(impact.getResolution());
        vo.setExceptionReason(impact.getExceptionReason());
        vo.setHandledBy(impact.getHandledBy());
        vo.setHandledAt(CanalService.str(impact.getHandledAt()));
        return vo;
    }

    private static String targetLabel(MaintenanceImpact impact, Map<Long, IrrigationPlan> plans,
                                      Map<Long, Allotment> allotments) {
        if ("PLAN".equals(impact.getTargetType())) {
            IrrigationPlan plan = plans == null ? null : plans.get(impact.getTargetId());
            return plan == null ? "计划#" + impact.getTargetId()
                    : plan.getPlanCode() + " " + CanalService.nvl(plan.getPlanName());
        }
        Allotment allotment = allotments == null ? null : allotments.get(impact.getTargetId());
        return allotment == null ? "配水#" + impact.getTargetId()
                : "配水 " + allotment.getAllotDate() + " " + allotment.getFlow() + "m³/s×" + allotment.getHours() + "h";
    }

    /**
     * 每个冲击对象挑一条最有代表性的冲击项（甘特条角标用）：
     * 待避让优先，其次已处理（顺延 / 拆分 / 例外保留），最后已恢复；同级取最新一条。
     */
    public static Map<String, MaintenanceImpact> briefByTarget(List<MaintenanceImpact> all) {
        Map<String, MaintenanceImpact> map = new LinkedHashMap<>();
        for (MaintenanceImpact impact : all) {
            String key = impact.getTargetType() + "#" + impact.getTargetId();
            MaintenanceImpact current = map.get(key);
            if (current == null || rank(impact) > rank(current)
                    || (rank(impact) == rank(current) && impact.getId() > current.getId())) {
                map.put(key, impact);
            }
        }
        return map;
    }

    private static int rank(MaintenanceImpact impact) {
        if (impact.isPending()) {
            return 2;
        }
        return "RESTORED".equals(impact.getStatus()) ? 0 : 1;
    }
}
