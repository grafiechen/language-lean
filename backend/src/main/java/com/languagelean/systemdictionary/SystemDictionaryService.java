package com.languagelean.systemdictionary;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** 管理通用系统字典，并用停用而非删除保护历史业务快照。 */
@Service
public class SystemDictionaryService {
    private final SystemDictionaryRepository dictionaries;
    private final SystemDictionaryItemRepository items;
    private final EntityManager entities;

    SystemDictionaryService(SystemDictionaryRepository dictionaries, SystemDictionaryItemRepository items,
                            EntityManager entities) {
        this.dictionaries = dictionaries;
        this.items = items;
        this.entities = entities;
    }

    /** 后台列表显示全部字典及选项数量。 */
    @Transactional(readOnly = true)
    public List<Summary> list() {
        return dictionaries.findAll(Sort.by("code")).stream()
                .map(dictionary -> new Summary(dictionary.code, dictionary.displayName, dictionary.description,
                        dictionary.enabled, dictionary.version,
                        items.findByDictionaryCodeOrderBySortOrderAscDisplayNameAsc(dictionary.code).size()))
                .toList();
    }

    /** 详情包含停用项，编辑旧快照时仍能解释原值。 */
    @Transactional(readOnly = true)
    public View detail(String code) {
        var dictionary = findDictionary(code);
        return view(dictionary);
    }

    /** 新增或更新字典定义；更新必须提交读取时的版本。 */
    @Transactional
    public View save(String rawCode, DictionaryEdit edit) {
        var code = dictionaryCode(rawCode);
        if (edit == null) throw bad("请填写系统字典配置");
        var existing = dictionaries.findById(code);
        var dictionary = existing.orElseGet(() -> SystemDictionaryEntity.create(code));
        if (existing.isPresent() && (edit.version() == null || edit.version() != dictionary.version)
                || existing.isEmpty() && edit.version() != null)
            throw new ResponseStatusException(CONFLICT, "系统字典已被修改，请刷新后重试");
        dictionary.update(required(edit.displayName(), 80, "字典名称"),
                text(edit.description(), 500, "字典说明"), edit.enabled());
        if (existing.isEmpty()) entities.persist(dictionary);
        entities.flush();
        return view(dictionary);
    }

    /** 新增选项时 value 成为业务快照中的稳定值。 */
    @Transactional
    public View createItem(String rawCode, ItemEdit edit) {
        var code = dictionaryCode(rawCode);
        var dictionary = findDictionary(code);
        if (edit == null || edit.version() != null) throw bad("新增选项不能携带版本");
        var value = required(edit.value(), 200, "选项值");
        if (items.existsByDictionaryCodeAndValue(code, value))
            throw new ResponseStatusException(CONFLICT, "该选项值已存在");
        var item = SystemDictionaryItemEntity.create(code, value);
        update(item, edit);
        entities.persist(item);
        entities.flush();
        return view(dictionary);
    }

    /** 更新选项保留 id 和 value，避免历史快照与配置脱节。 */
    @Transactional
    public View updateItem(String rawCode, UUID id, ItemEdit edit) {
        var code = dictionaryCode(rawCode);
        var dictionary = findDictionary(code);
        var item = items.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "系统字典选项不存在"));
        if (!item.dictionaryCode.equals(code)) throw new ResponseStatusException(NOT_FOUND, "系统字典选项不存在");
        if (edit == null || edit.version() == null || edit.version() != item.version)
            throw new ResponseStatusException(CONFLICT, "系统字典选项已被修改，请刷新后重试");
        if (edit.value() != null && !edit.value().trim().equals(item.value)) throw bad("已创建选项的保存值不能修改");
        update(item, edit);
        entities.flush();
        return view(dictionary);
    }

    /** 选项通用字段执行同一套长度和排序边界校验。 */
    private void update(SystemDictionaryItemEntity item, ItemEdit edit) {
        if (edit.sortOrder() < -100000 || edit.sortOrder() > 100000) throw bad("排序值必须在 -100000 到 100000 之间");
        item.update(required(edit.displayName(), 200, "显示名称"),
                text(edit.description(), 500, "选项说明"), edit.sortOrder(), edit.enabled());
    }

    /** API 视图按配置顺序返回选项，前端可直接渲染下拉框。 */
    private View view(SystemDictionaryEntity dictionary) {
        var options = items.findByDictionaryCodeOrderBySortOrderAscDisplayNameAsc(dictionary.code).stream()
                .map(item -> new ItemView(item.id, item.value, item.displayName, item.description,
                        item.sortOrder, item.enabled, item.version)).toList();
        return new View(dictionary.code, dictionary.displayName, dictionary.description,
                dictionary.enabled, dictionary.version, options);
    }

    private SystemDictionaryEntity findDictionary(String code) {
        return dictionaries.findById(code).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "系统字典不存在"));
    }
    private String dictionaryCode(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{1,63}")) throw bad("系统字典代码格式不正确");
        return value;
    }
    private String required(String value, int max, String name) {
        var result = text(value, max, name);
        if (result.isBlank()) throw bad(name + "不能为空");
        return result;
    }
    private String text(String value, int max, String name) {
        var result = value == null ? "" : value.trim();
        if (result.length() > max) throw bad(name + "超过长度限制");
        return result;
    }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(BAD_REQUEST, message); }

    /** null 版本用于新建，更新时必须携带版本。 */
    public record DictionaryEdit(String displayName, String description, boolean enabled, Long version) {}
    /** value 仅在新增时生效，更新时必须保持原值。 */
    public record ItemEdit(String value, String displayName, String description, int sortOrder,
                           boolean enabled, Long version) {}
    /** 字典列表摘要。 */
    public record Summary(String code, String displayName, String description, boolean enabled,
                          long version, int itemCount) {}
    /** 单个字典的完整配置。 */
    public record View(String code, String displayName, String description, boolean enabled,
                       long version, List<ItemView> items) {}
    /** 下拉选项配置及乐观锁版本。 */
    public record ItemView(UUID id, String value, String displayName, String description,
                           int sortOrder, boolean enabled, long version) {}
}
