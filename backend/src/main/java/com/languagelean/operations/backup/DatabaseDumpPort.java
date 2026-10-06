package com.languagelean.operations.backup;

import java.nio.file.Path;

/** 生产使用真实PostgreSQL一致性快照；测试通过替身隔离数据库连接与云服务。 */
interface DatabaseDumpPort {
    boolean configured();
    Path encryptedDump() throws Exception;
}
