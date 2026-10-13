/**
 * 订单上下文 · 应用层。
 *
 * <p>用例编排与事务边界。依赖方向：application → domain（+ 端口）。重试在事务之外且跨 Bean。
 */
package com.fuzuyang.securities.order.application;
