package com.fuzuyang.securities.order.infrastructure.entity;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code t_upstream_command} 行映射。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpstreamCommandDO {

    private Long id;
    private String orderNo;
    private String commandType;
    private String transport;
    private String payload;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextRetryAt;
    private String owner;
    private LocalDateTime claimedAt;
    private String lastError;
    private LocalDateTime createdAt;
}
