package lk.gov.ciaboc.hrms.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** Trilingual, administrator-editable notification template (FR-NOT-003). Placeholders use {{name}}. */
@Getter
@Setter
@Entity
@Table(name = "notification_template")
public class NotificationTemplate extends BaseEntity {
    @Column(nullable = false, unique = true, length = 60)
    private String code;

    @Column(length = 300)
    private String description;

    @Column(name = "title_en", nullable = false, length = 300)
    private String titleEn;
    @Column(name = "title_si", length = 300)
    private String titleSi;
    @Column(name = "title_ta", length = 300)
    private String titleTa;

    @Column(name = "body_en", nullable = false, length = 2000)
    private String bodyEn;
    @Column(name = "body_si", length = 2000)
    private String bodySi;
    @Column(name = "body_ta", length = 2000)
    private String bodyTa;

    @Column(name = "send_email", nullable = false)
    private boolean sendEmail;

    @Column(name = "send_sms", nullable = false)
    private boolean sendSms;

    @Column(nullable = false)
    private boolean active = true;

    public String title(String lang) {
        return pick(lang, titleEn, titleSi, titleTa);
    }

    public String body(String lang) {
        return pick(lang, bodyEn, bodySi, bodyTa);
    }

    private static String pick(String lang, String en, String si, String ta) {
        if ("si".equals(lang) && si != null && !si.isBlank()) return si;
        if ("ta".equals(lang) && ta != null && !ta.isBlank()) return ta;
        return en;
    }
}
