package lk.gov.ciaboc.hrms.attendance;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** Holiday calendar maintained by ATT_CONFIG; used by attendance processing and leave day counting. */
@Getter
@Setter
@Entity
@Table(name = "public_holiday")
public class PublicHoliday extends BaseEntity {
    public enum HolidayType {PUBLIC, BANK, MERCANTILE, SPECIAL}

    @Column(name = "holiday_date", nullable = false, unique = true)
    private LocalDate holidayDate;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "name_si", length = 200)
    private String nameSi;

    @Column(name = "name_ta", length = 200)
    private String nameTa;

    @Enumerated(EnumType.STRING)
    @Column(name = "holiday_type", nullable = false, length = 20)
    private HolidayType holidayType = HolidayType.PUBLIC;

    @Column(length = 500)
    private String remarks;
}
