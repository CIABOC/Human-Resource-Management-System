import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Card, Loading, TextArea } from '@/components/ui';
import type { Appraisal } from './types';
import { APPRAISAL_STEPS, AppraisalShell, Chip, fmtPct, Note, StatusTrail } from './shared';

const STOPS = ['Very Bad', 'Bad', 'Average', 'Good', 'Very Good'];
interface R { rating: number; na: boolean; justification: string }

/** Screen 4: 10 criteria on a Very Bad - Very Good scale, N/A option, descriptor and a required justification. */
export default function AssessmentPage() {
  const { id } = useParams();
  const toast = useToast();
  const { data: a, loading, error, setData } = useApi<Appraisal>(`/appraisal/appraisals/${id}`);
  const [r, setR] = useState<Record<number, R>>({});
  const [strengths, setStrengths] = useState('');
  const [improvements, setImprovements] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!a) return;
    setR(Object.fromEntries(a.behaviour.map((c) => [c.criterionId, { rating: c.rating ?? 3, na: c.notApplicable, justification: c.justification ?? '' }])));
    setStrengths(a.strengths ?? '');
    setImprovements(a.improvements ?? '');
  }, [a]);

  const editable = !!a?.flags.canAssess;
  const missing = a ? a.behaviour.filter((c) => !r[c.criterionId]?.na && !(r[c.criterionId]?.justification ?? '').trim()).length : 0;
  const applicable = a ? a.behaviour.filter((c) => !r[c.criterionId]?.na) : [];
  const preview = applicable.length ? (applicable.reduce((s, c) => s + (r[c.criterionId]?.rating ?? 3) - 1, 0) / applicable.length / 4) * 100 : null;

  const save = async (submit: boolean) => {
    setBusy(true);
    try {
      const { data } = await api.put<Appraisal>(`/appraisal/appraisals/${id}/behaviour`, {
        submit, strengths, improvements,
        ratings: a!.behaviour.map((c) => ({ criterionId: c.criterionId, rating: r[c.criterionId]?.na ? null : r[c.criterionId]?.rating, notApplicable: r[c.criterionId]?.na ?? false, justification: r[c.criterionId]?.justification })),
      });
      setData(data);
      toast(submit ? 'Behavioural assessment submitted' : 'Draft saved', 'success');
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <AppraisalShell title="Behavioural assessment">
      {error && <Alert kind="danger">{error}</Alert>}
      {loading && !a ? <Loading /> : a && (
        <>
          <div className="stack" style={{ gap: 10 }}>
            <StatusTrail steps={APPRAISAL_STEPS} current={a.status} />
            <div className="apr-chips"><Chip>{a.employee.name}</Chip><Chip>{a.period.name}</Chip>
              <Link className="apr-chip" to={`/appraisal/verify/${a.id}`}>KPI verification: {a.verificationCompletedAt ? 'completed' : 'pending'}</Link>
              {a.behaviourStatus === 'SUBMITTED' && <Chip tone="success">Submitted</Chip>}</div>
          </div>
          {a.flags.actingFor && <Note>You are acting on behalf of {a.flags.actingFor} under a delegation.</Note>}
          {editable && missing > 0 && (
            <Note kind="danger">{missing} {missing === 1 ? 'criterion needs' : 'criteria need'} a justification, and every rating needs a justification before you can submit. N/A criteria are left out of the score.</Note>
          )}
          <Card title="Rate each applicable criterion" accent>
            <div className="apr-criteria">
              <div className="apr-criterion apr-criterion-head" aria-hidden>
                <span>Criterion</span><span className="apr-scale-stops">{STOPS.map((s) => <span key={s}>{s}</span>)}</span><span style={{ textAlign: 'center' }}>N/A</span><span>Justification (required)</span>
              </div>
              {a.behaviour.map((c) => {
                const x = r[c.criterionId] ?? { rating: 3, na: false, justification: '' };
                return (
                  <div key={c.criterionId} className={`apr-criterion ${x.na ? 'is-na' : ''}`}>
                    <div><div className="name">{c.name}</div><div className="desc">{x.na ? 'Not applicable' : c.descriptors[x.rating - 1] ?? ''}</div></div>
                    <div className="apr-scale">
                      <input type="range" min={1} max={5} step={1} value={x.rating} disabled={!editable || x.na} aria-label={`${c.name} rating`}
                        aria-valuetext={STOPS[x.rating - 1]} onChange={(e) => setR({ ...r, [c.criterionId]: { ...x, rating: Number(e.target.value) } })} />
                      <div className="apr-scale-stops">{STOPS.map((s, i) => <span key={s} style={{ fontWeight: i + 1 === x.rating && !x.na ? 700 : 400, color: i + 1 === x.rating && !x.na ? 'var(--navy-800)' : undefined }}>{s}</span>)}</div>
                    </div>
                    <div className="na"><input type="checkbox" aria-label={`${c.name} not applicable`} checked={x.na} disabled={!editable}
                      onChange={(e) => setR({ ...r, [c.criterionId]: { ...x, na: e.target.checked } })} /></div>
                    <input className={`input ${editable && !x.na && !x.justification.trim() ? 'invalid' : ''}`} placeholder={x.na ? 'Not needed' : 'Required'}
                      aria-label={`${c.name} justification`} disabled={!editable || x.na} value={x.justification}
                      onChange={(e) => setR({ ...r, [c.criterionId]: { ...x, justification: e.target.value } })} />
                  </div>
                );
              })}
            </div>
          </Card>
          <div className="grid grid-2">
            {editable ? <>
              <TextArea label="Strengths" rows={3} value={strengths} onChange={setStrengths} />
              <TextArea label="Areas requiring improvement" rows={3} value={improvements} onChange={setImprovements} />
            </> : <>
              <Card title="Strengths"><p style={{ margin: 0 }}>{strengths || '—'}</p></Card>
              <Card title="Areas requiring improvement"><p style={{ margin: 0 }}>{improvements || '—'}</p></Card>
            </>}
          </div>
          <div className="row">
            {editable && <>
              <button className="btn btn-navy" disabled={busy || missing > 0} onClick={() => void save(true)}>Submit assessment</button>
              <button className="btn btn-outline" disabled={busy} onClick={() => void save(false)}>Save draft</button>
            </>}
            <span className="muted" style={{ fontSize: 12.5 }}>Behavioural result {editable ? '(preview)' : ''}: <b>{fmtPct(editable ? preview : a.behaviourScore)}</b></span>
          </div>
          <p className="muted" style={{ fontSize: 12 }}>Ratings, descriptors and justifications are visible to authorized officers. The employee sees only the finalized assessment.</p>
        </>
      )}
    </AppraisalShell>
  );
}
