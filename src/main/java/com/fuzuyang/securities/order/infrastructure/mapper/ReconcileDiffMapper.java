package com.fuzuyang.securities.order.infrastructure.mapper;

import com.fuzuyang.securities.order.infrastructure.entity.ReconcileDiffDO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 对账差异表访问（{@code dedupe_key} 唯一，重复拒绝不产生新行）。
 */
@Mapper
public interface ReconcileDiffMapper {

    int upsert(ReconcileDiffDO diff);

    ReconcileDiffDO selectByDedupeKey(@Param("dedupeKey") String dedupeKey);

    /** 缺口修复认领：OPEN 且到期，{@code FOR UPDATE SKIP LOCKED}。 */
    List<ReconcileDiffDO> selectRepairableGaps(@Param("limit") int limit);

    int markGapResolved(@Param("id") long id);

    /** 认领：推后 60 秒，避免处理期间被重复认领。 */
    int markGapClaimed(@Param("id") long id);

    /** 仍在缺：退避后再试。 */
    int markGapDeferred(@Param("id") long id, @Param("attemptCount") int attemptCount,
            @Param("backoffSeconds") long backoffSeconds);

    /** 用尽：置 FAILED。 */
    int markGapFailed(@Param("id") long id, @Param("attemptCount") int attemptCount);

    long countOpenGaps();
}
