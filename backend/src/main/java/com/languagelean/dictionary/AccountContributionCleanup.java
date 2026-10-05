package com.languagelean.dictionary;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 注销只清理私人申请和贡献身份，公开基准正文、外部来源及许可保持独立。 */
@Service
public class AccountContributionCleanup {
    private final ContributionRepository submissions;
    private final DictionaryRevisionRepository revisions;
    AccountContributionCleanup(ContributionRepository submissions, DictionaryRevisionRepository revisions) { this.submissions = submissions; this.revisions = revisions; }
    /** 等待正在进行的审核结束后再匿名，保证并发通过的公开版本也被处理。 */
    @Transactional
    public void anonymize(UUID owner) { submissions.lockAccountSubmissions(owner); revisions.anonymizeContributor(owner); }
}
