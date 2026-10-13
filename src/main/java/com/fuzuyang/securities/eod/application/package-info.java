/**
 * 日终上下文 · 应用层。
 *
 * <p>批次编排、步骤抢占/接管（owner + 租约 + CAS）与三个幂等步骤。依赖方向：application → domain（+ 端口）。
 */
package com.fuzuyang.securities.eod.application;
