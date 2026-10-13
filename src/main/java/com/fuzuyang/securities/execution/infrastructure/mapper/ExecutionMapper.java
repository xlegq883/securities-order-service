package com.fuzuyang.securities.execution.infrastructure.mapper;

import com.fuzuyang.securities.execution.infrastructure.entity.ExecutionDO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 成交明细访问（{@code uk_execution_id} 为兜底唯一键）。
 */
@Mapper
public interface ExecutionMapper {

    int insert(ExecutionDO execution);

    /** 该 executionId 已被哪个订单占用（事前检查；未占用返回 null）。 */
    String selectOrderNoByExecutionId(@Param("executionId") String executionId);

    /** 缺口扫描的驱动集：窗口内出现过的订单（不按状态过滤）。 */
    List<String> selectDrivingOrders(@Param("windowSeconds") long windowSeconds, @Param("limit") int limit);
}
