package com.languagelean.operations;

import com.languagelean.accounts.AccountRuntimeStatusService;
import com.languagelean.audio.AudioService;
import java.time.*;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

/** 管理员系统用量快照；只读数据库与配置，不发送邮件或调用付费云接口。 */
@Service
public class SystemUsageService {
    private final SystemUsageRepository rows;
    private final AudioService audio;
    private final AccountRuntimeStatusService accounts;
    private final boolean secureCookie, masterConfigured;
    SystemUsageService(SystemUsageRepository rows, AudioService audio, AccountRuntimeStatusService accounts,
                       @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie,
                       @Value("${app.password-transport.master-key:}") String master) {
        this.rows = rows; this.audio = audio; this.accounts = accounts; this.secureCookie = secureCookie; this.masterConfigured = !master.isBlank();
    }
    /** 同一数据库快照内计算各项计数，月份不接受路径、SQL或未来月份。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View overview(String selectedMonth) {
        var now = Instant.now(); var month = month(selectedMonth, now);
        var from = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        var to = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        var accountRows = rows.accounts(); var dictionaryRows = rows.dictionary(); var audioRows = rows.audio(now); var usage = rows.generation(from, to);
        var availability = audio.availability(); var mail = accounts.status();
        return new View(now, month.toString(), "UTC", rows.firstGeneration(),
            new Accounts(count(accountRows, "ACTIVE"), count(accountRows, "DISABLED")),
            new Dictionary(count(dictionaryRows, "PUBLISHED"), count(dictionaryRows, "DRAFT"), count(dictionaryRows, "BANNED")),
            new Audio(number(audioRows[0]), number(audioRows[1]), rows.audioVersions(), number(audioRows[2]), number(audioRows[3]), rows.cleanup()),
            new Generation(number(usage[0]), number(usage[1]), number(usage[2]), number(usage[3]), number(usage[4]), number(usage[5]), number(usage[6]), number(usage[7])),
            new Work(rows.contributions(), rows.feedback()),
            new Configuration(availability.googleEnabled(), availability.storageConfigured(), mail.mailConfigured(), mail.passwordRecoveryConfigured(), secureCookie, masterConfigured));
    }
    static YearMonth month(String value, Instant now) {
        var current = YearMonth.from(now.atZone(ZoneOffset.UTC));
        if (value == null || value.isBlank()) return current;
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}")) throw new DateTimeException("invalid format");
            var selected = YearMonth.parse(value);
            if (selected.getYear() < 1970 || selected.isAfter(current)) throw new DateTimeException("invalid range");
            return selected;
        } catch (DateTimeException invalid) { throw new ResponseStatusException(BAD_REQUEST, "月份必须为1970年起至当前UTC月份，格式YYYY-MM"); }
    }
    private static long count(List<Object[]> rows, String status) { return rows.stream().filter(row -> row[0].toString().equals(status)).mapToLong(row -> number(row[1])).sum(); }
    private static long number(Object value) { return ((Number) value).longValue(); }
    public record View(Instant generatedAt, String month, String timezone, Instant firstRecordedRequest,
        Accounts accounts, Dictionary dictionary, Audio audio, Generation generation, Work work, Configuration configuration) {}
    public record Accounts(long active, long disabled) {}
    public record Dictionary(long published, long draft, long banned) {}
    public record Audio(long resources, long resourcesWithVersion, long versions, long generating, long failed, long pendingCleanup) {}
    public record Generation(long requests, long inputCharacters, long returned, long responseBytes, long ready, long failed, long discarded, long unconfirmed) {}
    public record Work(long pendingContributions, long pendingAudioFeedback) {}
    public record Configuration(boolean googleTtsEnabled, boolean r2Configured, boolean mailConfigured, boolean passwordRecoveryConfigured, boolean secureSessionCookie, boolean persistentPasswordMasterConfigured) {}
}
