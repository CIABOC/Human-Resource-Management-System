import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { BellRing, Plus, Wand2 } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, Modal, PageHeader, SelectField, StatusBadge } from '@/components/ui';
import { DataTable, Column, fmtDate, fmtDateTime } from '@/components/DataTable';
import { EmployeeSelect, OrgUnitSelect } from '@/components/pickers';
import { EVALUATION_STATUSES, EvaluationListItem, EvaluatorOption, Period } from './types';

const score = (e: EvaluationListItem) => (e.overallScore != null ? `${e.overallScore} (${e.overallPercent}%)` : '—');

const COLS: Column<EvaluationListItem>[] = [
  { key: 'periodName', header: 'Period' },
  { key: 'employeeNo', header: 'Emp. No' },
  { key: 'employeeName', header: 'Employee' },
  { key: 'designation', header: 'Designation' },
  { key: 'orgUnitName', header: 'Unit' },
  { key: 'evaluatorName', header: 'Evaluator', render: (e) => e.evaluatorName ?? <span className="badge badge-warning">Unassigned</span> },
  { key: 'score', header: 'Score', render: score },
  { key: 'status', header: 'Status', render: (e) => <StatusBadge status={e.status} /> },
];

/** HR overview of evaluations within data scope (PER_VIEW). */
export function EvaluationsPage() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const toast = useToast();
  const { can } = useAuth();
  const manage = can('PER_MANAGE');
  const periods = useApi<Period[]>('/performance/periods');
  const [periodId, setPeriodId] = useState('');
  const [status, setStatus] = useState('');
  const [orgUnitCode, setOrgUnitCode] = useState('');
  const { data, loading, error, reload } = useApi<EvaluationListItem[]>('/performance/evaluations', { periodId, status, orgUnitCode });
  const [create, setCreate] = useState<{ periodId: string; employeeId: string; evaluatorUserId: string } | null>(null);
  const evaluators = useApi<EvaluatorOption[]>(create && manage ? '/performance/evaluators' : null);
  const [err, setErr] = useState<string | null>(null);

  const generate = async () => {
    if (!periodId) return toast('Choose a period first', 'error');
    try {
      const r = await api.post<{ created: number; existing: number; skipped: string[]; withoutEvaluator: number }>(`/performance/periods/${periodId}/generate`);
      toast(`${r.data.created} created, ${r.data.existing} already existed, ${r.data.skipped.length} skipped, ${r.data.withoutEvaluator} need an evaluator`, 'success');
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };
  const remind = async () => {
    try {
      const r = await api.post<{ evaluatorsReminded: number; evaluationsPending: number; employeesReminded: number }>('/performance/reminders', { periodId: periodId ? Number(periodId) : null });
      toast(`Reminded ${r.data.evaluatorsReminded} evaluator(s) (${r.data.evaluationsPending} evaluations) and ${r.data.employeesReminded} employee(s)`, 'success');
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };
  const save = async () => {
    if (!create) return;
    setErr(null);
    try {
      const r = await api.post<{ id: number }>('/performance/evaluations', {
        periodId: Number(create.periodId), employeeId: Number(create.employeeId), evaluatorUserId: create.evaluatorUserId ? Number(create.evaluatorUserId) : null,
      });
      nav(`/performance/evaluations/${r.data.id}`);
    } catch (e) {
      setErr(errorMessage(e));
    }
  };

  return (
    <>
      <PageHeader eyebrow={t('per.section', 'Performance')} title={t('per.evaluations', 'Evaluations')} subtitle="Confidential — every view is recorded in the audit trail"
        actions={manage && (
          <div className="row">
            <button className="btn btn-outline" onClick={() => void remind()}><BellRing /> Send reminders</button>
            <button className="btn btn-outline" onClick={() => void generate()}><Wand2 /> Generate for period</button>
            <button className="btn btn-gold" onClick={() => setCreate({ periodId, employeeId: '', evaluatorUserId: '' })}><Plus /> New evaluation</button>
          </div>
        )} />
      <Card pad={false} title={
        <div className="filters" style={{ margin: 0 }}>
          <SelectField label="Period" value={periodId} onChange={setPeriodId} placeholder="All" options={(periods.data ?? []).map((p) => ({ value: String(p.id), label: p.name }))} />
          <SelectField label="Status" value={status} onChange={setStatus} placeholder="All" options={EVALUATION_STATUSES.map((s) => ({ value: s, label: s }))} />
          <OrgUnitSelect value={orgUnitCode} onChange={setOrgUnitCode} placeholder="All units" />
        </div>
      }>
        {error && <Alert kind="danger">{error}</Alert>}
        <DataTable<EvaluationListItem> rows={data} loading={loading} onRowClick={(e) => nav(`/performance/evaluations/${e.id}`)} columns={COLS} />
      </Card>
      {create && (
        <Modal title="New evaluation" onClose={() => setCreate(null)} footer={
          <><button className="btn btn-outline" onClick={() => setCreate(null)}>Cancel</button><button className="btn btn-primary" onClick={() => void save()}>Create</button></>
        }>
          {err && <Alert kind="danger">{err}</Alert>}
          <div className="form-grid">
            <SelectField label="Period" value={create.periodId} onChange={(v) => setCreate({ ...create, periodId: v })} required
              options={(periods.data ?? []).filter((p) => p.status === 'OPEN').map((p) => ({ value: String(p.id), label: p.name }))} />
            <EmployeeSelect value={create.employeeId} onChange={(v) => setCreate({ ...create, employeeId: v })} required />
            <SelectField label="Evaluator" value={create.evaluatorUserId} onChange={(v) => setCreate({ ...create, evaluatorUserId: v })} placeholder="Default: division head"
              options={(evaluators.data ?? []).map((u) => ({ value: String(u.userId), label: u.name }))} className="span-all" />
          </div>
          <p className="faint">The evaluation model is taken from the employee's category.</p>
        </Modal>
      )}
    </>
  );
}

/** Evaluations assigned to the current user as evaluator. */
export function AssignedPage() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const { data, loading, error } = useApi<EvaluationListItem[]>('/performance/evaluations/assigned');
  return (
    <>
      <PageHeader eyebrow={t('per.section', 'Performance')} title={t('per.assigned', 'Evaluations to complete')} subtitle="Employees you are assigned to evaluate" />
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false}>
        <DataTable<EvaluationListItem> rows={data} loading={loading} onRowClick={(e) => nav(`/performance/evaluations/${e.id}`)}
          columns={COLS.filter((c) => c.key !== 'evaluatorName')} />
      </Card>
    </>
  );
}

/** The employee's own finalized evaluations (FR-PER-007). */
export function MyEvaluationsPage() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const { data, loading, error } = useApi<EvaluationListItem[]>('/performance/my/evaluations');
  return (
    <>
      <PageHeader title={t('per.my', 'My evaluations')} subtitle="Finalized performance evaluations — review, comment and acknowledge" />
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false}>
        <DataTable<EvaluationListItem> rows={data} loading={loading} onRowClick={(e) => nav(`/performance/evaluations/${e.id}`)}
          empty="No finalized evaluations yet."
          columns={[
            { key: 'periodName', header: 'Period' },
            { key: 'evaluatorName', header: 'Evaluator' },
            { key: 'score', header: 'Score', render: score },
            { key: 'finalizedAt', header: 'Finalized', render: (e) => fmtDate(e.finalizedAt) },
            { key: 'acknowledgedAt', header: 'Acknowledged', render: (e) => (e.acknowledgedAt ? fmtDateTime(e.acknowledgedAt) : <span className="badge badge-gold">Awaiting you</span>) },
          ]} />
      </Card>
    </>
  );
}
