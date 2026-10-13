package com.fuzuyang.securities.execution.application.gap;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缺口判定八用例。
 */
class GapDetectorTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private GapProps props() {
        GapProps props = new GapProps();
        props.setGraceCount(3);
        props.setGraceSeconds(30);
        return props;
    }

    @Test
    void noMissing() {
        assertThat(GapDetector.minMissingSeq(List.of(1L, 2L, 3L))).isEqualTo(-1);
        assertThat(GapDetector.shouldReport(List.of(1L, 2L, 3L), NOW.minusSeconds(5), NOW, props())).isFalse();
    }

    @Test
    void internalGapBelowThreshold() {
        assertThat(GapDetector.minMissingSeq(List.of(1L, 2L, 4L, 5L))).isEqualTo(3);
        assertThat(GapDetector.shouldReport(List.of(1L, 2L, 4L, 5L), NOW.minusSeconds(5), NOW, props())).isFalse();
    }

    @Test
    void internalGapAtThreshold() {
        assertThat(GapDetector.shouldReport(List.of(1L, 2L, 4L, 5L, 6L), NOW.minusSeconds(5), NOW, props()))
                .isTrue();
    }

    @Test
    void startingGapOnlyByTime() {
        List<Long> seqs = List.of(5L, 6L, 7L);
        assertThat(GapDetector.minMissingSeq(seqs)).isEqualTo(1);
        assertThat(GapDetector.shouldReport(seqs, NOW.minusSeconds(31), NOW, props())).isTrue();
        assertThat(GapDetector.shouldReport(seqs, NOW.minusSeconds(5), NOW, props())).isFalse();
    }

    @Test
    void outOfOrderFilledNoGap() {
        assertThat(GapDetector.shouldReport(List.of(1L, 2L, 3L, 4L), NOW.minusSeconds(60), NOW, props())).isFalse();
    }

    @Test
    void multipleHolesReportsSmallest() {
        assertThat(GapDetector.minMissingSeq(List.of(1L, 2L, 4L, 8L))).isEqualTo(3);
    }

    @Test
    void singleRecord() {
        assertThat(GapDetector.minMissingSeq(List.of(1L))).isEqualTo(-1);
        assertThat(GapDetector.minMissingSeq(List.of(5L))).isEqualTo(1);
    }

    @Test
    void rejectedSeqCountsAsReceived() {
        // "已收到" = 已应用 ∪ 被拒；被拒序号补齐后无缺口
        assertThat(GapDetector.minMissingSeq(List.of(1L, 2L, 3L, 4L))).isEqualTo(-1);
        assertThat(GapDetector.minMissingSeq(List.of(1L, 3L))).isEqualTo(2);
    }
}
