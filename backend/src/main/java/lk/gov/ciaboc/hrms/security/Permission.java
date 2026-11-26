package lk.gov.ciaboc.hrms.security;

/**
 * Capability catalogue. Permissions are fixed in code because each one guards code paths; which ROLE holds which
 * permission is configuration data (FR-UAM-008) maintained through User and Access Management.
 */
public enum Permission {
    DASHBOARD_VIEW("View dashboards"),
    SELF_SERVICE("Employee self-service (own profile, leave, payslips, evaluations)"),

    EST_VIEW("View organizational structure, designations, positions and cadre"),
    EST_MANAGE("Submit establishment and cadre changes"),
    EST_CADRE_EXCEPTION("Authorise cadre reductions below filled + acting posts"),
    LEGAL_VIEW("View legal documents and provisions"),
    LEGAL_MANAGE("Maintain legal documents, provisions and amendments"),

    EMP_VIEW("View employee records within data scope"),
    EMP_MANAGE("Create and update employee records"),
    EMP_RESTRICTED("Access restricted record classes (highly confidential documents, restricted staff)"),
    EMP_IMPORT("Bulk import legacy employee data"),

    REC_VIEW("View recruitment"),
    REC_MANAGE("Manage vacancies, applicants, examinations, interviews and appointments"),
    SOR_MANAGE("Prepare Schemes of Recruitment (SOR) and examination correspondence"),
    SOR_PUBLISH("Publish approved SORs and vacancies (Publisher role only)"),

    LEAVE_VIEW("View leave of others within data scope"),
    LEAVE_MANAGE("Manage leave balances and records"),
    LEAVE_CONFIG("Configure leave types and entitlements"),

    ATT_VIEW("View attendance within data scope"),
    ATT_MANAGE("Manage attendance, corrections and no-pay"),
    ATT_CONFIG("Configure working schedules and holidays"),

    PAY_VIEW("View payroll information"),
    PAY_MANAGE("Prepare payroll, salaries, deductions and loans"),
    PAY_CONFIG("Configure salary scales, allowances and deduction rates"),

    BEN_VIEW("View benefits and insurance"),
    BEN_MANAGE("Manage insurance schemes, coverage and claims"),

    PER_VIEW("View performance evaluations (HR)"),
    PER_MANAGE("Configure evaluation models and periods"),
    PER_EVALUATE("Evaluate assigned employees"),

    APR_MANAGE("Administer performance appraisals (start cycles, assign officers, decide special cases, reopening and appeals)"),
    APR_POLICY_APPROVE("Approve appraisal policy changes (weighting and rating scale)"),
    APR_OVERVIEW("View the aggregated performance appraisal dashboard"),
    APR_REPORT("Drill down into and export appraisal results (restricted)"),
    APR_ADMIN("Technical administration of appraisal (HRMS synchronization); no appraisal content"),

    TRN_VIEW("View training"),
    TRN_MANAGE("Manage training programmes and nominations"),

    WFL_APPROVE("Act on approval requests"),
    WFL_CONFIG("Configure approval workflows"),

    MDM_MANAGE("Maintain master data"),
    RPT_VIEW("View and export reports"),
    RPT_SENSITIVE("View and export sensitive reports (payroll, performance)"),
    NOTIF_CONFIG("Configure notification templates"),
    UAM_MANAGE("Manage users, roles and access"),
    AUDIT_VIEW("View audit history"),
    SYS_CONFIG("Manage system settings and integrations"),

    EXT_VIEW("View transfers, promotions, retirements and certificates"),
    EXT_MANAGE("Manage transfers, promotions, retirements and certificates");

    private final String description;

    Permission(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
