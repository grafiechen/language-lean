package com.languagelean.audio;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
/** 限量查询删除任务，避免一次读取全部对象键。 */
interface AudioObjectCleanupRepository extends JpaRepository<AudioObjectCleanup, UUID> {
    List<AudioObjectCleanup> findTop16ByOrderByCreatedAtAscIdAsc();
}
