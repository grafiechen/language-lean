package com.languagelean.systemdictionary;

import jakarta.persistence.*;

/** 通用系统字典定义；代码是前后端约定的稳定身份。 */
@Entity
@Table(name = "system_dictionary")
class SystemDictionaryEntity {
    @Id
    @Column(length = 64)
    String code;
    @Column(name = "display_name", nullable = false, length = 80)
    String displayName;
    @Column(nullable = false, length = 500)
    String description;
    @Column(nullable = false)
    boolean enabled;
    @Version
    long version;

    /** 仅供 JPA 创建实体。 */
    protected SystemDictionaryEntity() {}

    /** 新建字典时一次确定稳定代码。 */
    static SystemDictionaryEntity create(String code) {
        var dictionary = new SystemDictionaryEntity();
        dictionary.code = code;
        return dictionary;
    }

    /** 展示信息可维护，代码始终保持不变。 */
    void update(String displayName, String description, boolean enabled) {
        this.displayName = displayName;
        this.description = description;
        this.enabled = enabled;
    }
}
