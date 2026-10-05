package com.languagelean.learning;

import com.languagelean.reviews.domain.Rating;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 固定 Java 结果用于浏览器交叉验证；普通测试不会重生成预期值。 */
class FsrsParityTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final UUID ITEM = UUID.fromString("12345678-1234-4321-9876-123456789abc");

    /** 参数不同、学习/重学、当天/跨日及最大间隔都必须与已保存的官方结果一致。 */
    @Test
    void officialSchedulerMatchesSavedFixtures() throws Exception {
        // 仅显式设置此属性时生成；更新算法版本时按文档重新生成并审查两端差异。
        var output = System.getProperty("fsrs.fixture.output");
        if (output != null) Files.writeString(Path.of(output), json.writerWithDefaultPrettyPrinter().writeValueAsString(fixtures()));
        var source = output == null ? getClass().getResourceAsStream("/fsrs-parity.json") : Files.newInputStream(Path.of(output));
        assertNotNull(source, "跨语言基准文件缺失");
        try (source) {
            var rows = json.readTree(source);
            assertTrue(rows.size() >= 80);
            for (var row : rows) {
                var profile = row.get("profile");
                var scheduler = new FsrsScheduler(profile.get("desiredRetention").asDouble(), profile.get("maximumIntervalDays").asInt());
                var result = scheduler.schedule(ITEM, row.get("baseline").asText(), Rating.valueOf(row.get("rating").asText()),
                        Instant.parse(row.get("completedAt").asText()));
                var expected = row.get("stateAfter");
                var actual = (tools.jackson.databind.node.ObjectNode) json.readTree(result.state());
                // ARM 与 x86 的数学库末位可能不同；与浏览器交叉验证采用相同精度，日期和状态仍严格相等。
                for (var field : new String[] {"stability", "difficulty"}) {
                    assertEquals(expected.get(field).asDouble(), actual.get(field).asDouble(), 1e-10,
                            row.get("label").asText() + "/" + field);
                    actual.set(field, expected.get(field));
                }
                assertEquals(expected, actual, row.get("label").asText());
                assertEquals(json.readTree(json.writeValueAsString(scheduler.profile())), profile);
            }
        }
    }

    /** 生成器只依赖官方 Java 库，不调用浏览器实现，避免两份代码互相复制错误。 */
    private java.util.List<Fixture> fixtures() {
        var rows = new ArrayList<Fixture>();
        for (var retention : new double[] {0.9, 0.8}) {
            var scheduler = new FsrsScheduler(retention, retention == 0.9 ? 36500 : 30);
            for (var first : Rating.values()) {
                var baseline = "{}";
                var time = Instant.parse("2025-01-01T00:00:00Z");
                for (int index = 0; index < 18; index++) {
                    var rating = index == 0 ? first : switch (index % 6) {
                        case 0 -> Rating.AGAIN;
                        case 1, 4 -> Rating.HARD;
                        default -> Rating.GOOD;
                    };
                    var result = scheduler.schedule(ITEM, baseline, rating, time);
                    rows.add(new Fixture(retention + "/" + first + "/" + index, ITEM, baseline, rating, time,
                            scheduler.profile(), json.readTree(result.state())));
                    baseline = result.state();
                    time = index % 3 == 0 ? time.plusSeconds(90) : index % 3 == 1 ? result.due() : time.plusSeconds(86400 * 15L);
                }
            }
        }
        return rows;
    }

    /** 保存明确输入和输出，供 Java 与 TypeScript 分别验证。 */
    record Fixture(String label, UUID itemId, String baseline, Rating rating, Instant completedAt,
                   FsrsScheduler.Profile profile, tools.jackson.databind.JsonNode stateAfter) {}
}
