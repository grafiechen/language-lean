package com.languagelean.languages;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将语言实体转换为稳定的公开 API DTO。 */
@Service
class LanguageService {
    private final LanguageRepository repository;
    LanguageService(LanguageRepository repository) { this.repository = repository; }

    /** 按语言代码排序并过滤未启用的题型。 */
    @Transactional(readOnly = true)
    List<LanguageController.LanguageView> enabledLanguages() {
        return repository.findByEnabledTrueOrderByCodeAsc().stream()
                .map(language -> new LanguageController.LanguageView(
                        language.getCode(), language.getDisplayName(), language.getPronunciationLocale(),
                        language.getReviewTypes().stream().filter(LanguageReviewTypeEntity::isEnabled)
                                .map(type -> new LanguageController.ReviewTypeView(type.getTypeId(), type.getContractVersion()))
                                .toList()))
                .toList();
    }
}
