package com.languagelean.learning;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

/** 严格UTF-8的个人CSV适配器：支持BOM、引号、逗号及多行字段，拒绝坏编码和不闭合字段。 */
final class LearningCsvParser {
    private LearningCsvParser() {}
    /** 保留物理起始行号，使多行例句中的错误也能定位到原文件。 */
    record Row(int line, List<String> fields) {}

    /** 全文件格式校验失败时不导入；业务字段错误由预检按行报告。 */
    static List<Row> parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 2 * 1024 * 1024)
            throw bad("CSV应为非空UTF-8文件，最大2MB");
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException error) { throw bad("CSV编码无效，请另存为UTF-8"); }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        var result = new ArrayList<Row>(); var fields = new ArrayList<String>(); var field = new StringBuilder();
        boolean quoted = false, closed = false;
        int line = 1, startLine = 1;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (quoted) {
                if (value == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else { quoted = false; closed = true; }
                } else {
                    if (value == '\r' || value == '\n') {
                        if (value == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                        field.append('\n'); line++;
                    } else field.append(value);
                }
            } else if (value == ',' || value == '\n' || value == '\r') {
                fields.add(field.toString()); field.setLength(0); closed = false;
                if (value != ',') {
                    add(result, startLine, fields); fields.clear();
                    if (value == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    startLine = ++line;
                }
            } else if (closed) { throw bad("第" + line + "行：关闭引号后只能跟逗号或换行"); }
            else if (value == '"') {
                if (!field.isEmpty()) throw bad("第" + line + "行：引号必须位于字段开头");
                quoted = true;
            } else field.append(value);
            if (field.length() > 12000 || fields.size() > 7) throw bad("第" + startLine + "行：字段过长或列数超过7");
        }
        if (quoted) throw bad("第" + startLine + "行：引号未闭合");
        if (!fields.isEmpty() || !field.isEmpty() || closed) { fields.add(field.toString()); add(result, startLine, fields); }
        if (result.isEmpty()) throw bad("CSV没有词条");
        var first = result.getFirst().fields();
        if (first.getFirst().equals("written")) {
            var headers = List.of("written", "reading", "gloss", "tags", "example", "exampleReading", "exampleTranslation");
            if (first.size() < 3 || first.size() > 7 || !first.equals(headers.subList(0, first.size())))
                throw bad("表头应按模板顺序：written,reading,gloss,tags,example,exampleReading,exampleTranslation");
            result.removeFirst();
        }
        if (result.isEmpty() || result.size() > 1000) throw bad("每批应包含1至1000个词条");
        return result;
    }

    /** 空白行忽略；复制字段后清空解析缓冲，不丢失末尾空字段。 */
    private static void add(List<Row> rows, int line, List<String> fields) {
        if (fields.stream().anyMatch(value -> !value.isBlank())) rows.add(new Row(line, List.copyOf(fields)));
        if (rows.size() > 1001) throw bad("每批最多1000个词条");
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(BAD_REQUEST, message); }
}
