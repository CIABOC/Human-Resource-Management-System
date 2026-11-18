package lk.gov.ciaboc.hrms.attendance;

import lk.gov.ciaboc.hrms.audit.AuditAction;
import lk.gov.ciaboc.hrms.audit.AuditService;
import lk.gov.ciaboc.hrms.common.ApiException;
import lk.gov.ciaboc.hrms.common.Json;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Holiday calendar (FR-ATT-012). Public API for other modules: {@link #holidaysBetween}. */
@Service
@RequiredArgsConstructor
public class HolidayService {
    private final PublicHolidayRepository repo;
    private final AuditService audit;

    public record HolidayRequest(LocalDate holidayDate, String name, String nameSi, String nameTa,
                                 PublicHoliday.HolidayType holidayType, String remarks) {
    }

    @Transactional(readOnly = true)
    public Set<LocalDate> holidaysBetween(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) return Set.of();
        return repo.findByHolidayDateBetweenOrderByHolidayDateAsc(from, to).stream()
                .map(PublicHoliday::getHolidayDate).collect(Collectors.toSet());
    }

    @Transactional(readOnly = true)
    public List<PublicHoliday> list(LocalDate from, LocalDate to) {
        return repo.findByHolidayDateBetweenOrderByHolidayDateAsc(from, to);
    }

    @Transactional
    public PublicHoliday create(HolidayRequest r) {
        validate(r);
        if (repo.existsByHolidayDate(r.holidayDate()))
            throw ApiException.conflict("A holiday is already defined on " + r.holidayDate());
        PublicHoliday h = new PublicHoliday();
        copy(h, r);
        repo.save(h);
        audit.log(AuditAction.CREATE, "PublicHoliday", h.getId(), "Holiday " + h.getHolidayDate() + " " + h.getName(), null, Json.write(r));
        return h;
    }

    @Transactional
    public PublicHoliday update(Long id, HolidayRequest r) {
        validate(r);
        PublicHoliday h = repo.findById(id).orElseThrow(() -> ApiException.notFound("Holiday", id));
        repo.findByHolidayDate(r.holidayDate()).filter(o -> !o.getId().equals(id)).ifPresent(o -> {
            throw ApiException.conflict("A holiday is already defined on " + r.holidayDate());
        });
        String before = snapshot(h);
        copy(h, r);
        audit.log(AuditAction.UPDATE, "PublicHoliday", id, "Holiday updated: " + h.getHolidayDate() + " " + h.getName(), before, snapshot(h));
        return h;
    }

    @Transactional
    public void delete(Long id) {
        PublicHoliday h = repo.findById(id).orElseThrow(() -> ApiException.notFound("Holiday", id));
        String before = snapshot(h);
        repo.delete(h);
        audit.log(AuditAction.DELETE, "PublicHoliday", id, "Holiday removed: " + h.getHolidayDate() + " " + h.getName(), before, null);
    }

    private static void validate(HolidayRequest r) {
        if (r == null || r.holidayDate() == null) throw ApiException.badRequest("Holiday date is required");
        if (r.name() == null || r.name().isBlank()) throw ApiException.badRequest("Holiday name is required");
    }

    private static void copy(PublicHoliday h, HolidayRequest r) {
        h.setHolidayDate(r.holidayDate());
        h.setName(r.name().trim());
        h.setNameSi(r.nameSi());
        h.setNameTa(r.nameTa());
        h.setHolidayType(r.holidayType() == null ? PublicHoliday.HolidayType.PUBLIC : r.holidayType());
        h.setRemarks(r.remarks());
    }

    private static String snapshot(PublicHoliday h) {
        return Json.write(new HolidayRequest(h.getHolidayDate(), h.getName(), h.getNameSi(), h.getNameTa(), h.getHolidayType(), h.getRemarks()));
    }
}
