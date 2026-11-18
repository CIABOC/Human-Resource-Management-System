package lk.gov.ciaboc.hrms.leave;

import lk.gov.ciaboc.hrms.workflow.WorkflowHandler;
import lk.gov.ciaboc.hrms.workflow.WorkflowInstance;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Workflow callbacks for process LEAVE_CANCEL: approved leave is cancelled and its days restored on approval. */
@Component
@RequiredArgsConstructor
public class LeaveCancellationHandler implements WorkflowHandler {
    private final LeaveService leave;

    @Override
    public String processCode() {
        return LeaveService.CANCEL_PROCESS;
    }

    @Override
    public void onApproved(WorkflowInstance wi) {
        leave.cancellationApproved(wi.getEntityId());
    }

    @Override
    public void onRejected(WorkflowInstance wi) {
        leave.cancellationClosed(wi.getEntityId(), LeaveApplication.CancellationStatus.REJECTED);
    }

    @Override
    public void onCancelled(WorkflowInstance wi) {
        leave.cancellationClosed(wi.getEntityId(), LeaveApplication.CancellationStatus.WITHDRAWN);
    }

    @Override
    public void onResubmitted(WorkflowInstance wi) {
        leave.cancellationClosed(wi.getEntityId(), LeaveApplication.CancellationStatus.PENDING);
    }

    @Override
    public Map<String, Object> summary(WorkflowInstance wi) {
        return leave.summaryOf(wi.getEntityId(), true);
    }
}
