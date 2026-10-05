package com.languagelean.learning;

import com.languagelean.reviews.domain.Rating;
import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.Scheduler;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 官方 FSRS-6 的适配层；使用实际答题时间和明确的基准状态，禁止内部取当前时间。 */
@Component
class FsrsScheduler {
    static final String VERSION = "FSRS-6/java-fsrs-1.0.0";
    private final Scheduler scheduler;

    /** 参数由部署配置提供；关闭随机扰动保证同基准、时间和评分得到相同结果。 */
    FsrsScheduler(@Value("${app.fsrs.desired-retention:0.9}") double retention,
                  @Value("${app.fsrs.maximum-interval-days:36500}") int maximumInterval) {
        if (!Double.isFinite(retention) || retention <= 0 || retention >= 1 || maximumInterval < 1)
            throw new IllegalArgumentException("FSRS 配置无效");
        scheduler = Scheduler.builder().desiredRetention(retention).maximumInterval(maximumInterval)
                .enableFuzzing(false).build();
    }

    /** 从历史快照计算一次最终评分；词条全局 UUID 不受算法内部整数 cardId 影响。 */
    Result schedule(UUID itemId, String baseline, Rating rating, Instant completedAt) {
        var card = "{}".equals(baseline)
                ? Card.builder().cardId(itemId.hashCode()).due(completedAt).build() : Card.fromJson(baseline);
        var updated = scheduler.reviewCard(card,
                io.github.openspacedrepetition.Rating.valueOf(rating.name()), completedAt).card();
        return new Result(updated.toJson(), updated.getDue(), scheduler.toJson());
    }

    /** 离线客户端保存实际参数和学习步骤，不能自行猜测部署环境的默认值。 */
    Profile profile() {
        return new Profile(1, VERSION, scheduler.getDesiredRetention(), scheduler.getMaximumInterval(),
                scheduler.getParameters().clone(),
                java.util.Arrays.stream(scheduler.getLearningSteps()).mapToLong(java.time.Duration::toMillis).toArray(),
                java.util.Arrays.stream(scheduler.getRelearningSteps()).mapToLong(java.time.Duration::toMillis).toArray(),
                scheduler.isEnableFuzzing());
    }

    /** 版本化的离线算法参数；数组使用毫秒，避免不同语言对 Duration 的解析差异。 */
    record Profile(int schemaVersion, String algorithmVersion, double desiredRetention, int maximumIntervalDays,
                   double[] parameters, long[] learningStepsMillis, long[] relearningStepsMillis, boolean enableFuzzing) {}

    /** 调度结果和参数快照一并写入事件历史。 */
    record Result(String state, Instant due, String configuration) {}
}
