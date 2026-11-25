package lk.gov.ciaboc.hrms.workflow;

import java.util.Map;

/**
 * Implemented by each module that routes records through approval. Callbacks run inside the approver's transaction:
 * throwing an exception from onApproved aborts the approval (e.g. leave balance no longer sufficient).
 */
public interface WorkflowHandler {
    /** Process code(s) this handler serves, e.g. "LEAVE". */
    String processCode();

    default void onApproved(WorkflowInstance instance) {
    }

    default void onRejected(WorkflowInstance instance) {
    }

    default void onReturned(WorkflowInstance instance) {
    }

    default void onResubmitted(WorkflowInstance instance) {
    }

    default void onCancelled(WorkflowInstance instance) {
    }

    /** Label/value pairs shown to approvers on the approval screen. Only include what approvers may see. */
    default Map<String, Object> summary(WorkflowInstance instance) {
        return Map.of();
    }

    /**
     * Request attributes that routing rules may test (REQ 10.6), e.g. {@code days} of a leave application or
     * {@code amount} of a claim. Keys are plain names (no {@code initiator.}/{@code subject.} prefix — those are supplied
     * by the engine from the organization structure). Values should be numbers, booleans or short strings.
     * Called when the request is submitted, resubmitted or migrated; failures are logged and ignored.
     */
    default Map<String, Object> routingAttributes(WorkflowInstance instance) {
        return Map.of();
    }
}
