import { Link } from 'react-router-dom';
import { useApi } from '@/hooks/useApi';
import type { ApprovalAction, WorkflowInstance } from '@/api/types';
import { fmtDateTime } from './DataTable';
import { StatusBadge } from './ui';

/**
 * Shows the approval trail(s) of a record, e.g. <WorkflowTrail entityType="LeaveApplication" entityId={id} />.
 * Only instances the user may see are returned by the API.
 */
export function WorkflowTrail({ entityType, entityId }: { entityType: string; entityId: number }) {
  const { data } = useApi<WorkflowInstance[]>('/workflow/history', { entityType, entityId });
  if (!data || data.length === 0) return <div className="faint">No approval history.</div>;
  return (
    <div className="stack">
      {data.map((wi) => (
        <InstanceTrail key={wi.id} wi={wi} />
      ))}
    </div>
  );
}

function InstanceTrail({ wi }: { wi: WorkflowInstance }) {
  const { data } = useApi<{ actions: ApprovalAction[] }>(`/workflow/instances/${wi.id}`);
  return (
    <div>
      <div className="row between" style={{ marginBottom: 8 }}>
        <Link to={`/approvals/${wi.id}`}>Request #{wi.id}</Link>
        <StatusBadge status={wi.status} />
      </div>
      <ActionTimeline actions={data?.actions ?? []} />
    </div>
  );
}

export function ActionTimeline({ actions }: { actions: ApprovalAction[] }) {
  return (
    <ul className="timeline">
      {actions.map((a) => (
        <li key={a.id}>
          <div className="row between">
            <strong>{a.action.charAt(0) + a.action.slice(1).toLowerCase()}</strong>
            <span className="when">{fmtDateTime(a.actedAt)}</span>
          </div>
          <div className="muted">
            {a.actorName}
            {a.onBehalfOfName ? ` on behalf of ${a.onBehalfOfName}` : ''}
            {a.stageName ? ` · ${a.stageName}` : ''}
          </div>
          {a.comment && <div style={{ marginTop: 4 }}>“{a.comment}”</div>}
        </li>
      ))}
    </ul>
  );
}
