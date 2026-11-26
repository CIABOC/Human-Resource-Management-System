import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Card, Empty, Loading, TextArea } from '@/components/ui';
import { fmtDate } from '@/components/DataTable';
import type { Appraisal, MyDashboard } from './types';
import {
  APPRAISAL_STEPS, AppraisalShell, Bar, CalcPanel, Chip, Confidential, fmtPct, NoAppraisal, pretty, StatusTrail, SummaryCard,
} from './shared';

const AGREEMENT_LABEL: Record<string, string> = { DRAFT: 'In draft', SUBMITTED: 'Awaiting sign-off', SUPERVISOR_SIGNED: 'Awaiting approval', FINAL: 'Signed off' };
const STATUS_LABEL: Record<string, string> = {
  DRAFT: 'In progress', SUBMITTED: 'Under review', VERIFIED: 'With reviewing officer', APPROVED: 'Approved', ACKNOWLEDGED: 'Acknowledged', FINALIZED: 'Finalized',
};

/** Screen 1: the employee's home screen for the appraisal. */
export default function MyDashboardPage() {
  const { data, loading, error, setData } = useApi<MyDashboard>('/appraisal/my');
  const a = data?.appraisal ?? null;
  return (
    <AppraisalShell title="Employee dashboard">
      {error && <Alert kind="danger">{error}</Alert>}
      {loading && !data ? <Loading /> : !a ? <NoAppraisal /> : (
        <>
          <div className="stack" style={{ gap: 10 }}>
            <StatusTrail steps={APPRAISAL_STEPS} current={a.status} />
            <div className="apr-chips"><Confidential /><Chip>{a.period.name}</Chip></div>
          </div>
          <div className="apr-cards">
            <SummaryCard label="KPI achievement (verified)" value={fmtPct(a.verifiedScore)} hint="Calculated from verified KPIs" />
            <SummaryCard label="Self-reported" value={fmtPct(a.selfScore)} hint="Your own reported progress" />
            <SummaryCard label="Appraisal status" value={STATUS_LABEL[a.status] ?? pretty(a.status)}
              hint={a.finalScore != null ? `Final score ${a.finalScore} · ${a.category ?? ''}` : undefined} />
            <SummaryCard label="Performance agreement" value={AGREEMENT_LABEL[a.agreementStatus] ?? pretty(a.agreementStatus)} hint={`Version ${a.agreementVersion}`} />
          </div>
          {a.flags.canAcknowledge && <Acknowledge a={a} onDone={(x) => setData((d) => (d ? { ...d, appraisal: x } : d))} />}
          <Card title="Assigned KPIs" accent>
            {a.kpis.length === 0 ? <Empty text="No KPIs have been agreed yet."><Link className="btn btn-outline btn-sm" to="/appraisal/agreement">Open the agreement</Link></Empty> : (
              <div className="apr-table-wrap">
                <table className="apr-table">
                  <thead><tr><th>KPI</th><th className="num">Weight</th><th>Self-reported</th><th>Verified</th><th>Evidence</th><th>Status</th></tr></thead>
                  <tbody>
                    {a.kpis.map((k) => (
                      <tr key={k.id}>
                        <td><b>{k.title}</b><div className="muted" style={{ fontSize: 11.5 }}>{k.objective}</div></td>
                        <td className="num">{fmtPct(k.weight, 0)}</td>
                        <td><Bar value={k.selfProgress} /> <span className="muted" style={{ fontSize: 11.5 }}>{fmtPct(k.selfProgress, 0)}</span></td>
                        <td>{k.verifiedValue != null && k.decision !== 'RETURNED'
                          ? <><Bar value={k.verifiedValue} /> <span className="muted" style={{ fontSize: 11.5 }}>{fmtPct(k.verifiedValue, 0)}</span></>
                          : <Chip>Pending</Chip>}</td>
                        <td>{k.evidenceStatus}</td>
                        <td><Chip tone={k.chip === 'Returned' ? 'danger' : k.chip === 'Completed' ? 'success' : undefined}>{k.chip}</Chip></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
          <div className="grid grid-2">
            <Card title="Outstanding actions" accent>
              {data!.actions.length === 0 ? <Empty text="Nothing outstanding." /> : (
                <table className="apr-table">
                  <thead><tr><th>Action</th><th>Due</th></tr></thead>
                  <tbody>{data!.actions.map((x) => (
                    <tr key={x.action}><td><Link to={x.link}>{x.action}</Link></td><td>{fmtDate(x.due)}</td></tr>
                  ))}</tbody>
                </table>
              )}
            </Card>
            <Card title="Performance trend" accent>
              <Trend points={[...data!.trend, ...(a.status === 'FINALIZED' && a.finalScore != null && !data!.trend.some((t) => t.label === a.period.name)
                ? [{ label: a.period.name, value: a.finalScore }] : [])]} />
            </Card>
          </div>
          <CalcPanel kpiWeight={a.kpiWeight} behaviourWeight={a.behaviourWeight} />
        </>
      )}
    </AppraisalShell>
  );
}

export function Trend({ points }: { points: { label: string; value: number }[] }) {
  if (points.length === 0) return <Empty text="Finalized appraisals will appear here." />;
  return (
    <div className="apr-trend" role="img" aria-label={`Final scores: ${points.map((p) => `${p.label} ${p.value}`).join(', ')}`}>
      {points.map((p) => (
        <div key={p.label} className="apr-trend-col">
          <b>{Number(p.value).toFixed(1)}</b>
          <div className="apr-trend-bar" style={{ height: `${Math.max(4, Number(p.value))}%` }} />
          <span>{p.label}</span>
        </div>
      ))}
    </div>
  );
}

function Acknowledge({ a, onDone }: { a: Appraisal; onDone: (a: Appraisal) => void }) {
  const toast = useToast();
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const ack = async () => {
    setBusy(true);
    try {
      const { data } = await api.post<Appraisal>(`/appraisal/appraisals/${a.id}/acknowledge`, { comment });
      toast('Appraisal acknowledged and finalized', 'success');
      onDone(data);
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Card title="Your appraisal has been approved" accent>
      <div className="apr-cards" style={{ marginBottom: 14 }}>
        <SummaryCard label="KPI result" value={fmtPct(a.kpiScore)} />
        <SummaryCard label="Behavioural result" value={fmtPct(a.behaviourScore)} />
        <SummaryCard label="Final score" value={a.finalScore ?? '—'} hint={a.adjustment ? `Includes moderation of ${a.adjustment}` : undefined} />
        <SummaryCard label="Category" value={a.category ?? '—'} />
      </div>
      {a.reviewerComments && <p className="muted">Reviewing officer: {a.reviewerComments}</p>}
      <TextArea label="Your comments (optional)" value={comment} onChange={setComment} />
      <div className="row" style={{ marginTop: 12 }}>
        <button className="btn btn-primary" disabled={busy} onClick={() => void ack()}>Acknowledge appraisal</button>
        <span className="muted" style={{ fontSize: 12.5 }}>Acknowledging finalizes the appraisal. You can appeal within the appeal window afterwards.</span>
      </div>
    </Card>
  );
}
