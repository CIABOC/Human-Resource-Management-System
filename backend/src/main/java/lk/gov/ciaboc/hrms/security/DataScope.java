package lk.gov.ciaboc.hrms.security;

/** Data-scope limit applied on top of RBAC (FR-UAM-004). */
public enum DataScope {
    /** Whole institution. */
    ALL,
    /** The user's own division and its sub-units. */
    DIVISION,
    /** Only the user's own records. */
    SELF
}
