import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { Alert, Card, DL, Loading, PageHeader, StatusBadge } from '@/components/ui';
import { fmtDate, fmtDateTime } from '@/components/DataTable';
import { WorkflowTrail } from '@/components/WorkflowTrail';
import { employeeLabel } from '@/components/pickers';
import { MovementForm } from './MovementsPage';
import type { Movement } from './types';
import { titleCase } from './types';

export default function MovementDetailPage() {
  const { id } = useParams();
  const { t } = useTranslation();
  const { me, can } = useAuth();
  const { data: m, loading, error, reload } = useApi<Movement>(`/extended/movements/${id}`);
  const [edit, setEdit] = useState(false);

  if (loading && !m) return <Loading />;
  if (error || !m) return <Alert kind="danger">{error ?? 'Not found'}</Alert>;
  const canRevise = m.status === 'RETURNED' && can('EXT_MANAGE') && me?.username === m.createdBy;

  return (
    <>
      <PageHeader eyebrow={`${t('extended.movementDetail', 'Transfer / promotion request')} #${m.id}`}
        title={`${titleCase(m.requestType)}: ${employeeLabel(m.employee)}`}
        subtitle={<StatusBadge status={m.status} />}
        actions={
          <>
            {m.workflowInstanceId && <Link className="btn btn-outline" to={`/approvals/${m.workflowInstanceId}`}>Open approval</Link>}
            {canRevise && <button className="btn btn-primary" onClick={() => setEdit(true)}>Revise</button>}
          </>
        } />
      {m.cadreWarning && <Alert kind="warning"><strong>Cadre check:</strong> {m.cadreWarning}</Alert>}
      <div className="grid grid-2" style={{ alignItems: 'start' }}>
        <Card title="Request">
          <DL items={[
            ['Employee', employeeLabel(m.employee)],
            ['Current placement', `${m.fromDesignation ?? '—'}, ${m.fromOrgUnitName ?? '—'}${m.fromPosition ? ` (${m.fromPosition})` : ''}`],
            ...(m.requestType === 'CONFIRMATION' ? [] : [
              ['Proposed placement', `${m.targetDesignation ?? '—'}, ${m.targetOrgUnitName ?? '—'}${m.targetPosition ? ` (${m.targetPosition})` : ''}`] as [string, string],
            ]),
            [m.requestType === 'CONFIRMATION' ? 'Confirmation date' : 'Effective date', fmtDate(m.effectiveDate)],
            ['Reason', m.reason],
            ['Reference', m.reference],
            ['Requested by', `${m.createdBy} · ${fmtDateTime(m.createdAt)}`],
            ['Applied', m.appliedAt ? fmtDateTime(m.appliedAt) : null],
          ]} />
        </Card>
        <Card title="Approval trail">
          <WorkflowTrail entityType="MovementRequest" entityId={m.id} />
        </Card>
      </div>
      {edit && <MovementForm initial={m} onClose={() => setEdit(false)} onSaved={() => { setEdit(false); void reload(); }} />}
    </>
  );
}
