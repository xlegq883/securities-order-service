package com.fuzuyang.securities.order.infrastructure.entity;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code t_event_outbox} 行映射。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventOutboxDO {

    private Long id;
    private String aggregateType;
    private String aggregateId;
    private Long eventVersion;
    private String eventType;
    private String topic;
    private String messageKey;
    private String payload;
    private String dedupeKey;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String owner;
    private LocalDateTime claimedAt;
    private LocalDateTime createdAt;
}
