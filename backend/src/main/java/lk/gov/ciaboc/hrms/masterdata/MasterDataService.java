package lk.gov.ciaboc.hrms.masterdata;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MasterDataService {
    private final MasterDataItemRepository repo;
    /** Resolved lazily: checkers live in modules that themselves depend on this service. */
    private final ObjectProvider<MasterDataUsageChecker> usageCheckers;
    private final AuditService audit;

    public record ItemRequest(String code, String nameEn, String nameSi, String nameTa, String description,
                              Integer sortOrder, LocalDate effectiveFrom, LocalDate effectiveTo) {
    }

    @Transactional(readOnly = true)
    public List<MasterDataItem> list(String category, boolean activeOnly) {
        category(category);
        return activeOnly ? repo.findByCategoryAndActiveTrueOrderBySortOrderAscNameEnAsc(category)
                : repo.findByCategoryOrderBySortOrderAscNameEnAsc(category);
    }

    /** Validates that a code exists and is active in a category; used by modules on data entry (SYS-09). */
    @Transactional(readOnly = true)
    public void requireActive(MasterDataCategory category, String code, String fieldLabel) {
        if (code == null || code.isBlank()) return;
        if (!repo.existsByCategoryAndCodeAndActiveTrue(category.name(), code)) {
            throw ApiException.rule("INVALID_REFERENCE", fieldLabel + " '" + code + "' is not a valid active value");
        }
    }

    @Transactional
    public MasterDataItem create(String category, ItemRequest r) {
        category(category);
        if (r.code() == null || !r.code().matches("^[A-Z0-9_]{1,40}$"))
            throw ApiException.badRequest("Code must be 1-40 upper-case letters, digits or underscores");
        if (r.nameEn() == null || r.nameEn().isBlank()) throw ApiException.badRequest("English name is required");
        if (repo.findByCategoryAndCode(category, r.code()).isPresent()) throw ApiException.conflict("Code already exists in " + category);
        MasterDataItem i = new MasterDataItem();
        i.setCategory(category);
        i.setCode(r.code());
        apply(i, r);
        repo.save(i);
        audit.log(AuditAction.CREATE, "MasterDataItem", i.getId(), category + "/" + i.getCode() + " created", null, snapshot(i));
        return i;
    }

    @Transactional
    public MasterDataItem update(Long id, ItemRequest r) {
        MasterDataItem i = repo.findById(id).orElseThrow(() -> ApiException.notFound("Master data item", id));
        Map<String, Object> before = snapshot(i);
        apply(i, r);
        audit.log(AuditAction.UPDATE, "MasterDataItem", id, i.getCategory() + "/" + i.getCode() + " updated", before, snapshot(i));
        return i;
    }

    @Transactional
    public MasterDataItem setActive(Long id, boolean active) {
        MasterDataItem i = repo.findById(id).orElseThrow(() -> ApiException.notFound("Master data item", id));
        if (!active) {
            long used = usageCheckers.orderedStream().mapToLong(c -> c.usageCount(i.getCategory(), i.getCode())).sum();
            if (used > 0) {
                throw ApiException.rule("IN_USE", "'" + i.getNameEn() + "' is used by " + used
                        + " active record(s). Reassign them before deactivating.");
            }
        }
        i.setActive(active);
        if (!active && i.getEffectiveTo() == null) i.setEffectiveTo(LocalDate.now());
        if (active) i.setEffectiveTo(null);
        audit.log(active ? AuditAction.ACTIVATE : AuditAction.DEACTIVATE, "MasterDataItem", id,
                i.getCategory() + "/" + i.getCode() + (active ? " activated" : " deactivated"));
        return i;
    }

    private void apply(MasterDataItem i, ItemRequest r) {
        if (r.nameEn() == null || r.nameEn().isBlank()) throw ApiException.badRequest("English name is required");
        i.setNameEn(r.nameEn().trim());
        i.setNameSi(r.nameSi());
        i.setNameTa(r.nameTa());
        i.setDescription(r.description());
        i.setSortOrder(r.sortOrder() == null ? 0 : r.sortOrder());
        i.setEffectiveFrom(r.effectiveFrom() == null ? (i.getEffectiveFrom() == null ? LocalDate.now() : i.getEffectiveFrom()) : r.effectiveFrom());
        i.setEffectiveTo(r.effectiveTo());
        if (i.getEffectiveTo() != null && !i.getEffectiveTo().isAfter(i.getEffectiveFrom()))
            throw ApiException.badRequest("Effective-to must be after effective-from");
    }

    private static void category(String c) {
        try {
            MasterDataCategory.valueOf(c);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.notFound("Master data category", c);
        }
    }

    private static Map<String, Object> snapshot(MasterDataItem i) {
        return Map.of("code", i.getCode(), "nameEn", i.getNameEn(), "nameSi", String.valueOf(i.getNameSi()),
                "nameTa", String.valueOf(i.getNameTa()), "active", i.isActive(), "effectiveFrom", String.valueOf(i.getEffectiveFrom()),
                "effectiveTo", String.valueOf(i.getEffectiveTo()));
    }
}
