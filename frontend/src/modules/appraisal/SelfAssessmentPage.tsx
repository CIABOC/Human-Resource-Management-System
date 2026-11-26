import { useEffect, useState } from 'react';
import { api, errorMessage } from '@/api/client';
import { useToast } from '@/components/Toast';
import { Alert, Card, ConfirmDialog, Loading, TextArea } from '@/components/ui';
import { fmtDate } from '@/components/DataTable';
import type { Appraisal } from './types';
import { APPRAISAL_STEPS, AppraisalShell, Chip, NoAppraisal, Note, StatusTrail, useTargetAppraisal } from './shared';

/** Screen 10: self-reported achievement and a note per KPI, then achievements, challenges and comments. */
export default function SelfAssessmentPage() {
  const toast = useToast();
  const { appraisal: a, loading, error, setAppraisal } = useTargetAppraisal();
  const [lines, setLines] = useState<Record<number, { p: string; note: string }>>({});
  const [text, setText] = useState({ achievements: '', challenges: '', comments: '' });
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState(false);

  useEffect(() => {
    if (!a) return;
    setLines(Object.fromEntries(a.kpis.map((k) => [k.id, { p: k.selfProgress != null ? String(k.selfProgress) : '', note: k.selfNote ?? '' }])));
    setText({ achievements: a.selfAchievements ?? '', challenges: a.selfChallenges ?? '', comments: a.selfComments ?? '' });
  }, [a]);

  const editable = !!a?.flags.canSelfAssess;
  const save = async (submit: boolean) => {
    setBusy(true);
    try {
      const { data } = await api.put<Appraisal>(`/appraisal/appraisals/${a!.id}/self-assessment`, {
        lines: a!.kpis.filter((k) => !k.excepted).map((k) => ({ kpiId: k.id, selfProgress: lines[k.id]?.p === '' ? null : Number(lines[k.id]?.p), note: lines[k.id]?.note })),
        ...text, submit,
      });
      setAppraisal(data);
      toast(submit ? 'Self-assessment submitted to your supervisor' : 'Draft saved', 'success');
      setConfirm(false);
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <AppraisalShell title="Self-assessment">
      {error && <Alert kind="danger">{error}</Alert>}
      {loading && !a ? <Loading /> : !a ? <NoAppraisal /> : (
        <>
          <Note>Self-assessment closes on {fmtDate(a.selfAssessmentCloses)}. You cannot edit it after submission.</Note>
          <StatusTrail steps={APPRAISAL_STEPS} current={a.status} />
          <div className="apr-chips">
            <Chip>Visible to supervisor after submission</Chip>
            {a.selfStatus === 'SUBMITTED' && <Chip tone="success">Submitted</Chip>}
          </div>
          {a.agreementStatus !== 'FINAL' && <Alert kind="info">The self-assessment opens once the performance agreement is final.</Alert>}
          <Card title="Self-reported achievement by KPI" accent>
            <div className="apr-table-wrap">
              <table className="apr-table">
                <thead><tr><th>KPI</th><th>Self-reported (%)</th><th>Note</th></tr></thead>
                <tbody>{a.kpis.map((k) => (
                  <tr key={k.id}>
                    <td>{k.title}{k.excepted && <> <Chip tone="muted">Exception</Chip></>}</td>
                    <td><input className="input" type="number" min={0} max={100} placeholder="00%" aria-label={`Self-reported achievement for ${k.title}`}
                      disabled={!editable || k.excepted} value={lines[k.id]?.p ?? ''} onChange={(e) => setLines({ ...lines, [k.id]: { ...lines[k.id], p: e.target.value } })} /></td>
                    <td><input className="input" placeholder="Note" aria-label={`Note for ${k.title}`} disabled={!editable || k.excepted}
                      value={lines[k.id]?.note ?? ''} onChange={(e) => setLines({ ...lines, [k.id]: { ...lines[k.id], note: e.target.value } })} /></td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
          </Card>
          <div className="grid grid-3">
            {(['achievements', 'challenges', 'comments'] as const).map((f) => (
              editable ? <TextArea key={f} label={f.charAt(0).toUpperCase() + f.slice(1)} rows={4} value={text[f]} onChange={(v) => setText({ ...text, [f]: v })} />
                : <Card key={f} title={f.charAt(0).toUpperCase() + f.slice(1)}><p style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{text[f] || '—'}</p></Card>
            ))}
          </div>
          {editable && (
            <div className="row">
              <button className="btn btn-navy" disabled={busy} onClick={() => setConfirm(true)}>Submit self-assessment</button>
              <button className="btn btn-outline" disabled={busy} onClick={() => void save(false)}>Save draft</button>
            </div>
          )}
          {confirm && (
            <ConfirmDialog title="Submit self-assessment?" message="After submission your supervisor can see it and you can no longer edit it."
              confirmLabel="Submit" busy={busy} onCancel={() => setConfirm(false)} onConfirm={() => void save(true)} />
          )}
        </>
      )}
    </AppraisalShell>
  );
}
