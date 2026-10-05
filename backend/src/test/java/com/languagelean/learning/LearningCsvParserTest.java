package com.languagelean.learning;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 外部CSV边界测试，覆盖桌面导出的BOM、多行与严格UTF-8，不依赖数据库。 */
class LearningCsvParserTest {
    @Test void quotedFieldsAndPhysicalLineNumbers() {
        var rows = LearningCsvParser.parse(("\uFEFFwritten,reading,gloss,tags,example,exampleReading,exampleTranslation\r\n"
            + "猫,ねこ,\"猫,动物\",N2,\"猫が\r\nいます。\",,\"他说\"\"有猫\"\"\"\r\n\r\n犬,いぬ,狗\r\n").getBytes(StandardCharsets.UTF_8));
        assertEquals(2, rows.size()); assertEquals(2, rows.getFirst().line()); assertEquals(5, rows.getLast().line());
        assertEquals("猫,动物", rows.getFirst().fields().get(2)); assertEquals("猫が\nいます。", rows.getFirst().fields().get(4));
        assertEquals("他说\"有猫\"", rows.getFirst().fields().get(6));
    }
    @Test void rejectsMalformedEncodingQuotesAndOversizedBatches() {
        assertThrows(ResponseStatusException.class, () -> LearningCsvParser.parse(new byte[]{(byte)0xFF}));
        for (var text : new String[]{"猫,ねこ,\"未闭合", "猫,ねこ,\"猫\"x", "猫,ねこ,字\"面", "written,gloss,reading\n猫,猫,ねこ", "猫,ねこ,猫\n".repeat(1001)})
            assertThrows(ResponseStatusException.class, () -> LearningCsvParser.parse(text.getBytes(StandardCharsets.UTF_8)));
    }
}
