package lk.gov.ciaboc.hrms.recruitment.vacancy;

import jakarta.persistence.*;
import lk.gov.ciaboc.hrms.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Vacancy automation configuration and alert log (spec v2.1 REQ 5.1-5.3). */
public final class VacancyEntities {
    private VacancyEntities() {
    }

    /** A configurable vacancy trigger (REQ 5.1). Matching values are data, so new reasons need no code change. */
    @Getter
    @Setter
    @Entity(name = "RecVacancyTrigger")
    @Table(name = "rec_vacancy_trigger")
    public static class TriggerConfig extends BaseEntity {
        @Column(nullable = false, unique = true, length = 30)
        private String code;
        @Column(nullable = false, length = 150)
        private String name;
        @Column(name = "name_si", length = 150)
        private String nameSi;
        @Column(name = "name_ta", length = 150)
        private String nameTa;
        @Column(length = 500)
        private String description;
        @Column(name = "separation_reasons", length = 300)
        private String separationReasons;
        @Column(name = "placement_reasons", length = 300)
        private String placementReasons;
        @Column(name = "forecast_date_field", length = 30)
        private String forecastDateField;
        @Column(name = "employment_types", length = 300)
        private String employmentTypes;
        @Column(name = "cadre_effect", length = 10)
        private String cadreEffect;
        @Column(nullable = false)
        private boolean enabled = true;
        @Column(name = "sort_order", nullable = false)
        private int sortOrder;
    }

    /** Alert at a lead time before the vacancy date (0 = when the vacancy opens) to configurable recipients (REQ 5.2, 5.3). */
    @Getter
    @Setter
    @Entity(name = "RecVacancyAlertRule")
    @Table(name = "rec_vacancy_alert_rule")
    public static class AlertRule extends BaseEntity {
        @Column(nullable = false, length = 150)
        private String name;
        @Column(name = "lead_days", nullable = false)
        private int leadDays;
        /** null = every trigger. */
        @Column(name = "trigger_code", length = 30)
        private String triggerCode;
        /** ROLE:&lt;code&gt;, USER:&lt;username&gt;, EMPLOYEE:&lt;employee no&gt;, DIVISION_HEAD — comma separated. */
        @Column(nullable = false, length = 2000)
        private String recipients;
        @Column(name = "in_app", nullable = false)
        private boolean inApp = true;
        @Column(nullable = false)
        private boolean email = true;
        @Column(nullable = false)
        private boolean sms;
        @Column(nullable = false)
        private boolean active = true;
    }

    /** One alert to one recipient on one channel; delivery status is derived from the notification / outbound queue. */
    @Getter
    @Setter
    @Entity(name = "RecVacancyAlert")
    @Table(name = "rec_vacancy_alert")
    public static class AlertDelivery extends BaseEntity {
        @Column(name = "vacancy_id", nullable = false)
        private Long vacancyId;
        @Column(name = "rule_id", nullable = false)
        private Long ruleId;
        @Column(nullable = false, length = 10)
        private String level;
        @Column(name = "lead_days", nullable = false)
        private int leadDays;
        @Column(name = "recipient_user_id", nullable = false)
        private Long recipientUserId;
        @Column(name = "recipient_name", nullable = false, length = 200)
        private String recipientName;
        @Column(name = "recipient_basis", nullable = false, length = 120)
        private String recipientBasis;
        @Column(nullable = false, length = 10)
        private String channel;
        @Column(length = 200)
        private String address;
        @Column(name = "notification_id")
        private Long notificationId;
        @Column(name = "outbound_message_id")
        private Long outboundMessageId;
        /** SENT (in-app), QUEUED (e-mail/SMS) or SKIPPED (with detail). */
        @Column(nullable = false, length = 20)
        private String status;
        @Column(length = 500)
        private String detail;
        @Column(name = "sent_at", nullable = false)
        private Instant sentAt;
    }
}
