package lk.gov.ciaboc.hrms.extended.movement;

import lk.gov.ciaboc.hrms.workflow.WorkflowHandler;
import lk.gov.ciaboc.hrms.workflow.WorkflowInstance;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The workflow engine maps one handler per process code; the three movement processes share
 * {@link MovementService} for their callbacks.
 */
public final class MovementWorkflowHandlers {
    private MovementWorkflowHandlers() {
    }

    abstract static class Base implements WorkflowHandler {
        private final MovementService service;
        private final String code;

        Base(MovementService service, String code) {
            this.service = service;
            this.code = code;
        }

        @Override
        public String processCode() {
            return code;
        }

        @Override
        public void onApproved(WorkflowInstance wi) {
            service.onApproved(wi);
        }

        @Override
        public void onRejected(WorkflowInstance wi) {
            service.setStatus(wi, MovementRequest.Status.REJECTED);
        }

        @Override
        public void onReturned(WorkflowInstance wi) {
            service.setStatus(wi, MovementRequest.Status.RETURNED);
        }

        @Override
        public void onResubmitted(WorkflowInstance wi) {
            service.setStatus(wi, MovementRequest.Status.PENDING);
        }

        @Override
        public void onCancelled(WorkflowInstance wi) {
            service.setStatus(wi, MovementRequest.Status.CANCELLED);
        }

        @Override
        public Map<String, Object> summary(WorkflowInstance wi) {
            return service.summary(wi);
        }
    }

    @Component("extMovementTransferHandler")
    public static class Transfer extends Base {
        public Transfer(MovementService service) {
            super(service, MovementService.P_TRANSFER);
        }
    }

    @Component("extMovementPromotionHandler")
    public static class Promotion extends Base {
        public Promotion(MovementService service) {
            super(service, MovementService.P_PROMOTION);
        }
    }

    @Component("extMovementConfirmationHandler")
    public static class Confirmation extends Base {
        public Confirmation(MovementService service) {
            super(service, MovementService.P_CONFIRMATION);
        }
    }
}
