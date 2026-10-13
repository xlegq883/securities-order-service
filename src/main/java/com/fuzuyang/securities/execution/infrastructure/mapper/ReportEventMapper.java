package com.fuzuyang.securities.execution.infrastructure.mapper;

import com.fuzuyang.securities.execution.infrastructure.entity.ReportEventDO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 回报事件访问。
 */
@Mapper
public interface ReportEventMapper {

    int insert(ReportEventDO event);

    /** 被拒分支：同事务内把 {@code applied} 置 0（重复投递命中时不得改写原行）。 */
    int markAppliedZero(@Param("dedupeKey") String dedupeKey);

    /**
     * 缺口"已收到序号" = 已应用（{@code t_execution}）∪ 被拒（{@code t_report_event}）。
     * 只用 {@code t_execution} 会把被拒成交当缺失，产生永远修不掉的幻影缺口。
     */
    List<Long> selectReceivedSeqs(@Param("orderNo") String orderNo);

    /** 该订单最近一次成交回报时刻（时间条件触发用）。 */
    LocalDateTime selectLastExecutionReportAt(@Param("orderNo") String orderNo);
}
