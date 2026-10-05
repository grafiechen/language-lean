package com.languagelean.systemdictionary;

import jakarta.persistence.*;
import java.util.UUID;

/** 系统字典选项；业务快照保存 value，显示文字可独立修订。 */
@Entity
@Table(name = "system_dictionary_item")
class SystemDictionaryItemEntity {
    @Id
    UUID id;
    @Column(name = "dictionary_code", nullable = false, length = 64)
    String dictionaryCode;
    @Column(name = "item_value", nullable = false, length = 200)
    String value;
    @Column(name = "display_name", nullable = false, length = 200)
    String displayName;
    @Column(nullable = false, length = 500)
    String description;
    @Column(name = "sort_order", nullable = false)
    int sortOrder;
    @Column(nullable = false)
    boolean enabled;
    @Version
    long version;

    /** 仅供 JPA 创建实体。 */
    protected SystemDictionaryItemEntity() {}

    /** value 创建后固定，避免已保存的业务快照失去含义。 */
    static SystemDictionaryItemEntity create(String dictionaryCode, String value) {
        var item = new SystemDictionaryItemEntity();
        item.id = UUID.randomUUID();
        item.dictionaryCode = dictionaryCode;
        item.value = value;
        return item;
    }

    /** 维护显示信息、排序和可用性。 */
    void update(String displayName, String description, int sortOrder, boolean enabled) {
        this.displayName = displayName;
        this.description = description;
        this.sortOrder = sortOrder;
        this.enabled = enabled;
    }
}
