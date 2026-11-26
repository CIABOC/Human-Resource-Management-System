import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowUpRight } from 'lucide-react';
import { useApi } from '@/hooks/useApi';
import { Card, Empty } from '@/components/ui';
import type { AprContext, MyDashboard, QueueRow } from './types';
import { APPRAISAL_STEPS, Chip, Confidential, fmtNum, fmtPct, StatusTrail } from './shared';
import { ManagementOverview } from './ManagementPage';

/**
 * Performance appraisal on the home dashboard. Management (the Director General) sees the aggregated appraisal
 * dashboard, the appraisals waiting for them as reviewing officer and their own appraisal; others see their own
 * appraisal and their queues.
 */
export default function DashboardAppraisalPanel() {
  const { data: ctx } = useApi<AprContext>('/appraisal/context');
  const approvals = useApi<QueueRow[]>(ctx?.reviewer ? '/appraisal/approvals' : null);
  const my = useApi<MyDashboard>(ctx?.employeeId ? '/appraisal/my' : null);
  const [periodId, setPeriodId] = useState('');
  const [orgUnitCode, setOrgUnitCode] = useState('');
  const [designationId, setDesignationId] = useState('');
  if (!ctx) return null;
  const pending = (approvals.data ?? []).filter((r) => r.status === 'VERIFIED');
  const mine = my.data?.appraisal;
  if (!ctx.overview && !mine && pending.length === 0 && !ctx.supervisor) return null;

  return (
    <section className="stack" style={{ gap: 14 }} aria-labelledby="dash-apr-title">
      <div className="apr-dash-head">
        <div>
          <h2 id="dash-apr-title" style={{ margin: 0 }}>Performance appraisal</h2>
          <div className="muted" style={{ fontSize: 13 }}>{ctx.overview ? 'Aggregated across the Commission for the selected period' : 'Your appraisal and actions'}</div>
        </div>
        <div className="row">
          {ctx.overview && <Confidential />}
          <Link className="btn btn-outline btn-sm" to="/appraisal">Open performance appraisal <ArrowUpRight /></Link>
        </div>
      </div>

      {ctx.overview && (
        <Card accent>
          <div className="stack" style={{ gap: 14 }}>
            <ManagementOverview compact periods={ctx.periods} periodId={periodId || String(ctx.currentPeriodId ?? '')} setPeriodId={setPeriodId}
              orgUnitCode={orgUnitCode} setOrgUnitCode={setOrgUnitCode} designationId={designationId} setDesignationId={setDesignationId} />
            <div><Link className="btn btn-ghost btn-sm" to="/appraisal/management">Drill-down and exports <ArrowUpRight /></Link></div>
          </div>
        </Card>
      )}

      <div className="grid grid-2">
        {(ctx.reviewer || pending.length > 0) && (
          <Card title={`Awaiting your approval as reviewing officer (${pending.length})`} accent>
            {pending.length === 0 ? <Empty text="Nothing is waiting for your approval." /> : (
              <table className="apr-table">
                <thead><tr><th>Employee</th><th className="num">KPI</th><th className="num">Behavioural</th><th aria-label="Action" /></tr></thead>
                <tbody>{pending.slice(0, 6).map((r) => (
                  <tr key={r.appraisalId}><td><b>{r.employeeName}</b><div className="muted" style={{ fontSize: 11 }}>{r.designation}</div></td>
                    <td className="num">{fmtNum(r.kpiScore)}</td><td className="num">{fmtNum(r.behaviourScore)}</td>
                    <td><Link className="btn btn-navy btn-sm" to={`/appraisal/approvals/${r.appraisalId}`}>Review</Link></td></tr>
                ))}</tbody>
              </table>
            )}
          </Card>
        )}
        {ctx.supervisor && (
          <Card title="My team" accent>
            <p style={{ marginTop: 0 }}><b>{ctx.pendingVerifications}</b> appraisal(s) waiting for your verification.</p>
            <Link className="btn btn-outline btn-sm" to="/appraisal/team">Open supervisor dashboard</Link>
          </Card>
        )}
        {mine && (
          <Card title={`My appraisal · ${mine.period.name}`} accent>
            <div className="stack" style={{ gap: 10 }}>
              <StatusTrail steps={APPRAISAL_STEPS} current={mine.status} />
              <div className="apr-chips">
                <Chip>Agreement: {mine.agreementStatus === 'FINAL' ? 'signed off' : mine.agreementStatus.replace(/_/g, ' ').toLowerCase()}</Chip>
                <Chip>Self-reported {fmtPct(mine.selfScore)}</Chip>
                {mine.finalScore != null && <Chip tone="success">Final {mine.finalScore} · {mine.category}</Chip>}
                {!mine.supervisorUserId && <Chip tone="muted">No supervisor assigned</Chip>}
              </div>
              {(my.data?.actions ?? []).slice(0, 3).map((a) => <Link key={a.action} to={a.link} style={{ fontSize: 13 }}>{a.action} →</Link>)}
            </div>
          </Card>
        )}
      </div>
    </section>
  );
}
