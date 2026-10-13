package com.fuzuyang.securities.order.infrastructure.mapper;

import com.fuzuyang.securities.order.infrastructure.entity.EventOutboxDO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 事件发件箱访问（三阶段投递：短事务认领 → 事务外发送 → owner 守卫落结果）。
 */
@Mapper
public interface EventOutboxMapper {

    int insert(EventOutboxDO event);

    EventOutboxDO selectByDedupeKey(@Param("dedupeKey") String dedupeKey);

    List<EventOutboxDO> selectClaimable(@Param("leaseSeconds") long leaseSeconds, @Param("limit") int limit);

    int markInProgress(@Param("ids") List<Long> ids, @Param("owner") String owner);

    int markSent(@Param("id") long id, @Param("owner") String owner);

    int markFailed(@Param("id") long id, @Param("owner") String owner, @Param("status") String status,
            @Param("retryCount") int retryCount, @Param("backoffMicros") long backoffMicros);
}
