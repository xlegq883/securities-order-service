package com.fuzuyang.securities.common.util;

/**
 * 去重键的唯一来源。
 *
 * <p>回报去重键：成交类 {@code EXECUTION:{order_no}:{execution_id}}；订单回报与撤单确认类
 * {@code {report_type}:{order_no}:{exchange_seq}}。
 *
 * <p>事件去重键：order 侧 {@code {order_no}:{version}:ORDER_STATE_CHANGED}；
 * execution 侧 {@code {order_no}:{version}:EXECUTION_APPLIED:{execution_id}}。
 */
public final class DedupeKeys {

    public static final String EXECUTION = "EXECUTION";

    private DedupeKeys() {
    }

    public static String report(String reportType, String orderNo, Long exchangeSeq, String executionId) {
        if (EXECUTION.equals(reportType)) {
            return EXECUTION + ":" + orderNo + ":" + executionId;
        }
        return reportType + ":" + orderNo + ":" + exchangeSeq;
    }

    public static String orderChanged(String orderNo, long eventVersion) {
        return orderNo + ":" + eventVersion + ":ORDER_STATE_CHANGED";
    }

    public static String executionApplied(String orderNo, long eventVersion, String executionId) {
        return orderNo + ":" + eventVersion + ":EXECUTION_APPLIED:" + executionId;
    }
}
