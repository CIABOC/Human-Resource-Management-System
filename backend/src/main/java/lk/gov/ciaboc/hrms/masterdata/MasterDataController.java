package lk.gov.ciaboc.hrms.masterdata;

import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class MasterDataController {
    private final MasterDataService service;
    private final SettingsService settings;

    public record CategoryView(String code, String label) {
    }

    @GetMapping("/api/master-data/categories")
    public List<CategoryView> categories() {
        return Arrays.stream(MasterDataCategory.values()).map(c -> new CategoryView(c.name(), c.getLabel())).toList();
    }

    /** Any authenticated user can read reference data for drop-downs. */
    @GetMapping("/api/master-data/{category}")
    public List<MasterDataItem> list(@PathVariable String category, @RequestParam(defaultValue = "true") boolean activeOnly) {
        return service.list(category, activeOnly);
    }

    @PostMapping("/api/master-data/{category}")
    @PreAuthorize("hasAuthority('MDM_MANAGE')")
    public MasterDataItem create(@PathVariable String category, @RequestBody MasterDataService.ItemRequest r) {
        return service.create(category, r);
    }

    @PutMapping("/api/master-data/items/{id}")
    @PreAuthorize("hasAuthority('MDM_MANAGE')")
    public MasterDataItem update(@PathVariable Long id, @RequestBody MasterDataService.ItemRequest r) {
        return service.update(id, r);
    }

    @PostMapping("/api/master-data/items/{id}/status")
    @PreAuthorize("hasAuthority('MDM_MANAGE')")
    public MasterDataItem status(@PathVariable Long id, @RequestParam boolean active) {
        return service.setActive(id, active);
    }

    // ---- system settings ----

    @GetMapping("/api/admin/settings")
    @PreAuthorize("hasAuthority('SYS_CONFIG')")
    public List<SystemSetting> settings() {
        return settings.all();
    }

    public record SettingUpdate(@NotBlank String value) {
    }

    @PutMapping("/api/admin/settings/{key}")
    @PreAuthorize("hasAuthority('SYS_CONFIG')")
    public SystemSetting update(@PathVariable String key, @RequestBody SettingUpdate r) {
        return settings.update(key, r.value());
    }

    @GetMapping("/api/settings/client")
    public Map<String, Object> clientSettings() {
        return Map.of("sessionIdleMinutes", settings.getInt("session.idle-minutes", 15),
                "organizationName", settings.get("org.name", "Commission to Investigate Allegations of Bribery or Corruption"));
    }
}
