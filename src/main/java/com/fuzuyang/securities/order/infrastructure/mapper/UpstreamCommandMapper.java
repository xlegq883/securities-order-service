package com.fuzuyang.securities.order.infrastructure.mapper;

import com.fuzuyang.securities.order.infrastructure.entity.UpstreamCommandDO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 上游指令表访问（三阶段投递：短事务认领 → 事务外发送 → owner 守卫落结果）。
 */
@Mapper
public interface UpstreamCommandMapper {

    int insert(UpstreamCommandDO command);

    UpstreamCommandDO selectByOrderNoAndType(@Param("orderNo") String orderNo,
            @Param("commandType") String commandType);

    /** 认领批次：PENDING 到期，或 IN_PROGRESS 且租约过期（接管）。{@code FOR UPDATE SKIP LOCKED}。 */
    List<UpstreamCommandDO> selectClaimable(@Param("leaseSeconds") long leaseSeconds,
            @Param("limit") int limit);

    int markInProgress(@Param("ids") List<Long> ids, @Param("owner") String owner);

    int markSent(@Param("id") long id, @Param("owner") String owner);

    int markFailed(@Param("id") long id, @Param("owner") String owner, @Param("status") String status,
            @Param("attemptCount") int attemptCount, @Param("backoffMicros") long backoffMicros,
            @Param("lastError") String lastError);
}
