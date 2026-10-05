package com.languagelean.learning;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 注销事务的个人域锁边界；个人正文由账号外键级联删除，不复制到注销审计。 */
@Service
public class AccountLearningCleanup {
    private final UserLearningItemRepository items;
    private final PrivateEntryRepository entries;
    AccountLearningCleanup(UserLearningItemRepository items, PrivateEntryRepository entries) { this.items = items; this.entries = entries; }
    /** 与既有删除保持进度在前、私人来源在后的锁序，覆盖未加入单词本的词条。 */
    @Transactional
    public void lock(UUID owner) { items.lockAccountItems(owner); entries.lockAccountEntries(owner); }
}
