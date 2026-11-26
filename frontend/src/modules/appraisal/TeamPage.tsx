import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useApi } from '@/hooks/useApi';
import { Alert, Card, Empty, Loading, SelectField } from '@/components/ui';
import type { TeamDashboard } from './types';
import { AppraisalShell, Chip, Confidential, Note, SummaryCard, useAprContext } from './shared';

/** Screen 3: the supervisor's own team only, with the review queue. */
export default function TeamPage() {
  return (
    <AppraisalShell title="Supervisor dashboard">
      <Body />
    </AppraisalShell>
  );
}

function Body() {
  const { ctx } = useAprContext();
  const [periodId, setPeriodId] = useState(String(ctx?.currentPeriodId ?? ''));
  const { data, loading, error } = useApi<TeamDashboard>('/appraisal/team', { periodId });
  return (
    <>
      <div className="row between">
        <div className="apr-chips"><Chip>Your team only</Chip><Confidential /></div>
        <SelectField label="Appraisal period" value={periodId} onChange={setPeriodId} placeholder=""
          options={(ctx?.periods ?? []).map((p) => ({ value: String(p.id), label: p.name }))} />
      </div>
      {error && <Alert kind="danger">{error}</Alert>}
      {loading && !data ? <Loading /> : data && (
        <>
          <div className="apr-cards">
            <SummaryCard label="Employees" value={data.employees} />
            <SummaryCard label="Verifications pending" value={data.pendingVerifications} />
            <SummaryCard label="Overdue actions" value={data.overdueActions} />
            <SummaryCard label="Assessments completed" value={`${data.assessmentsCompleted} / ${data.assessmentsTotal}`} />
          </div>
          <Card title="Review queue" accent>
            {data.queue.length === 0 ? <Empty text="No team members have an appraisal in this period." /> : (
              <div className="apr-table-wrap">
                <table className="apr-table">
                  <thead><tr><th>Employee</th><th>KPIs verified</th><th>Self-assessment</th><th>Behavioural</th><th>Status</th><th>Action</th></tr></thead>
                  <tbody>{data.queue.map((r) => (
                    <tr key={r.appraisalId}>
                      <td><b>{r.employeeName}</b><div className="muted" style={{ fontSize: 11.5 }}>{r.employeeNo} · {r.designation}{r.delegatedFrom ? ` · for ${r.delegatedFrom}` : ''}</div></td>
                      <td>{r.kpisVerified} of {r.kpisTotal}</td>
                      <td>{r.selfStatus === 'SUBMITTED' ? 'Submitted' : 'Not submitted'}</td>
                      <td>{r.behaviourStatus === 'SUBMITTED' ? 'Submitted' : r.behaviourStatus === 'DRAFT' ? 'In progress' : 'Pending'}</td>
                      <td><Chip tone={r.overdue ? 'danger' : r.status === 'FINALIZED' ? 'success' : undefined}>{r.queueStatus}</Chip>
                        {r.finalScore != null && <div className="muted" style={{ fontSize: 11.5, marginTop: 3 }}>Score {r.finalScore} · {r.category}</div>}</td>
                      <td>
                        <div className="row" style={{ gap: 6 }}>
                          {r.agreementStatus !== 'FINAL'
                            ? <Link className="btn btn-outline btn-sm" to={`/appraisal/agreement?appraisal=${r.appraisalId}`}>Open agreement</Link>
                            : <>
                              <Link className="btn btn-outline btn-sm" to={`/appraisal/verify/${r.appraisalId}`}>Open verification</Link>
                              <Link className="btn btn-ghost btn-sm" to={`/appraisal/assess/${r.appraisalId}`}>Behavioural</Link>
                            </>}
                        </div>
                      </td>
                    </tr>
                  ))}</tbody>
                </table>
              </div>
            )}
          </Card>
          <section className="apr-panel">
            <h4>How achievement is decided</h4>
            <p>The supervisor verifies each KPI against its evidence. The overall achievement is calculated from the verified KPIs and their weights, and cannot be set manually.</p>
          </section>
          <Note>The review can go to the reviewing officer only after every KPI has a verification decision and the behavioural assessment is submitted.</Note>
        </>
      )}
    </>
  );
}
