package com.languagelean.dictionary;
import java.util.*;
import org.springframework.web.bind.annotation.*;
/** 公开词条贡献署名，仅返回已发布来源许可。 */
@RestController
class ContributionCreditController {
    private final ContributionService service;
    ContributionCreditController(ContributionService service) { this.service = service; }
    /** 补充来源独立显示，不覆盖基准词典原有许可。 */
    @GetMapping("/api/v1/dictionary/{id}/contributions") List<ContributionService.Credit> credits(@PathVariable UUID id) { return service.credits(id); }
}
