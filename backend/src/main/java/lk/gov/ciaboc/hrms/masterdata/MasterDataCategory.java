package lk.gov.ciaboc.hrms.masterdata;

/**
 * Categories of simple reference data. Richer configuration (leave types, schedules, salary scales, deduction types,
 * insurance schemes, evaluation models, workflows) lives in the owning module's own tables.
 */
public enum MasterDataCategory {
    EMPLOYEE_STATUS("Employee status"),
    EMPLOYMENT_TYPE("Employment type"),
    EMPLOYEE_CATEGORY("Employee category"),
    SERVICE_CATEGORY("Service category"),
    GRADE("Grade"),
    TITLE("Title"),
    GENDER("Gender"),
    CIVIL_STATUS("Civil status"),
    DOCUMENT_TYPE("Employee document type"),
    QUALIFICATION_TYPE("Qualification type"),
    LOAN_TYPE("Loan type"),
    BANK("Bank"),
    INSURANCE_CATEGORY("Insurance category"),
    LEGAL_TOPIC("Legal provision topic"),
    SEPARATION_REASON("Separation reason"),
    RELATIONSHIP("Relationship"),
    LOCATION("Location (Head Office / Regional)");

    private final String label;

    MasterDataCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
