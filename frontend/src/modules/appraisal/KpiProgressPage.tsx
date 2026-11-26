import { useEffect, useMemo, useRef, useState } from 'react';
import { Trash2, Upload } from 'lucide-react';
import { api, download, errorMessage } from '@/api/client';
import { useToast } from '@/components/Toast';
import { Alert, Card, Checkbox, Empty, Loading, SelectField, TextArea, TextField } from '@/components/ui';
import type { Appraisal, Kpi } from './types';
import { METHODS, methodLabel } from './types';
import { APPRAISAL_STEPS, AppraisalShell, Chip, fmtDayMonth, fmtPct, NoAppraisal, Note, StatusTrail, useTargetAppraisal } from './shared';

interface Form { done: boolean[]; completed: string; total: string; actualDate: string; quality: string; description: string; selfProgress: string; explanation: string }

const formFor = (k: Kpi): Form => {
  const d = k.progressData ?? {};
  return {
    done: k.milestones.map((_, i) => Boolean((d.milestonesDone as boolean[] | undefined)?.[i])),
    completed: d.completedTasks != null ? String(d.completedTasks) : '', total: d.totalTasks != null ? String(d.totalTasks) : '',
    actualDate: (d.actualDate as string) ?? '', quality: (d.qualityMeasure as string) ?? '', description: (d.description as string) ?? '',
    selfProgress: k.selfProgress != null ? String(k.selfProgress) : '', explanation: k.delayExplanation ?? '',
  };
};

/** Screen 2: record progress per KPI with method-specific fields, attach evidence and submit for verification. */
export default function KpiProgressPage() {
  const toast = useToast();
  const { appraisal: a, loading, error, setAppraisal } = useTargetAppraisal();
  const [kpiId, setKpiId] = useState<string>('');
  const [form, setForm] = useState<Form | null>(null);
  const [busy, setBusy] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);

  const kpi = useMemo(() => a?.kpis.find((k) => String(k.id) === kpiId) ?? null, [a, kpiId]);
  useEffect(() => {
    if (a && !kpiId && a.kpis.length) setKpiId(String((a.kpis.find((k) => k.behindTarget && !k.excepted) ?? a.kpis[0]).id));
  }, [a, kpiId]);
  useEffect(() => {
    if (kpi) setForm(formFor(kpi));
  }, [kpi]);

  const editable = !!a && !!kpi && a.flags.canUpdateProgress && !kpi.excepted && !['SUBMITTED', 'VERIFIED'].includes(kpi.progressStatus);
  const preview = useMemo(() => {
    if (!kpi || !form) return null;
    if (kpi.method === 'MILESTONE') return kpi.milestones.reduce((s, m, i) => s + (form.done[i] ? Number(m.weight) || 0 : 0), 0);
    if (kpi.method === 'TASK_COUNT') return Number(form.total) > 0 ? Math.round((Number(form.completed) / Number(form.total)) * 10000) / 100 : null;
    return form.selfProgress === '' ? null : Number(form.selfProgress);
  }, [kpi, form]);
  const evidenceOk = !!kpi && kpi.evidence.some((e) => e.status !== 'RETURNED');
  const needsExplanation = !!kpi && kpi.behindTarget;
  const canSubmit = editable && evidenceOk && kpi!.selfProgress != null && (!needsExplanation || !!kpi!.delayExplanation);

  const run = async (fn: () => Promise<{ data: Appraisal }>, ok: string) => {
    setBusy(true);
    try {
      const { data } = await fn();
      setAppraisal(data);
      toast(ok, 'success');
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  const save = () => run(() => api.put(`/appraisal/kpis/${kpi!.id}/progress`, {
    milestonesDone: form!.done, completedTasks: form!.completed === '' ? null : Number(form!.completed), totalTasks: form!.total === '' ? null : Number(form!.total),
    actualDate: form!.actualDate || null, qualityMeasure: form!.quality, description: form!.description,
    selfProgress: form!.selfProgress === '' ? null : Number(form!.selfProgress), delayExplanation: form!.explanation,
  }), 'Progress saved');
  const upload = async (f: File) => {
    const fd = new FormData();
    fd.append('file', f);
    await run(() => api.post(`/appraisal/kpis/${kpi!.id}/evidence`, fd, { headers: { 'Content-Type': 'multipart/form-data' } }), 'Evidence uploaded');
    if (fileRef.current) fileRef.current.value = '';
  };

  const submissions = a ? a.kpis.flatMap((k) => k.submissions.map((s) => ({ ...s, title: k.title }))).sort((x, y) => y.submittedAt.localeCompare(x.submittedAt)) : [];
  const behind = a?.kpis.filter((k) => k.behindTarget && !k.excepted && !k.delayExplanation && ['NOT_STARTED', 'IN_PROGRESS', 'RETURNED'].includes(k.progressStatus)) ?? [];

  return (
    <AppraisalShell title="KPI agreement and progress">
      {error && <Alert kind="danger">{error}</Alert>}
      {loading && !a ? <Loading /> : !a ? <NoAppraisal /> : (
        <>
          <StatusTrail steps={APPRAISAL_STEPS} current={a.status} />
          {behind.length > 0 && a.flags.canUpdateProgress && (
            <Note kind="danger">Explanation is required because {behind.map((k) => k.title).join(', ')} {behind.length > 1 ? 'are' : 'is'} behind {behind.length > 1 ? 'their targets' : 'its target'}.</Note>
          )}
          <div className="apr-chips">
            <Chip tone={a.agreementStatus === 'FINAL' ? undefined : 'danger'}>{a.agreementStatus === 'FINAL' ? 'Agreement signed off' : 'Agreement not yet final'}</Chip>
            <Chip tone={Number(a.totalKpiWeight) === 100 ? undefined : 'danger'}>Weightage total {fmtPct(a.totalKpiWeight, 0)}</Chip>
          </div>
          {a.agreementStatus !== 'FINAL' ? <Alert kind="info">Progress can be recorded once the performance agreement is signed off by everyone.</Alert> : a.kpis.length === 0 ? <Empty /> : (
            <div className="apr-split">
              <Card title={kpi ? `Update progress: ${kpi.title}` : 'Update progress'} accent>
                <div className="form-grid">
                  <SelectField label="KPI" value={kpiId} onChange={setKpiId} placeholder="" options={a.kpis.map((k) => ({ value: String(k.id), label: k.title }))} />
                  <SelectField label="Measurement method" value={kpi?.method ?? ''} onChange={() => undefined} disabled placeholder="" options={METHODS.map((m) => ({ value: m.value, label: m.label }))} />
                </div>
                {kpi && form && (
                  <div className="stack" style={{ gap: 12, marginTop: 12 }}>
                    <p className="muted" style={{ fontSize: 12, margin: 0 }}>
                      Fields change with the method. Task count: completed and total tasks. Timeliness: actual completion date. Quality: quality measure. Qualitative: description of achievement.
                      {kpi.target && <> Target: <b>{kpi.target}</b>.</>}
                    </p>
                    {kpi.excepted && <Alert kind="info">An exception was approved for this KPI; it is left out of the result.</Alert>}
                    {kpi.progressStatus === 'RETURNED' && kpi.verifierComment && <Alert kind="warning">Returned by your supervisor: {kpi.verifierComment}</Alert>}
                    {kpi.method === 'MILESTONE' && (
                      <fieldset className="stack" style={{ gap: 6, border: 0, padding: 0, margin: 0 }} disabled={!editable}>
                        <legend className="muted" style={{ fontSize: 12.5, marginBottom: 4 }}>Milestones completed</legend>
                        {kpi.milestones.map((m, i) => (
                          <Checkbox key={i} label={`${m.name} (${m.weight}%)`} checked={form.done[i] ?? false}
                            onChange={(v) => setForm({ ...form, done: form.done.map((x, j) => (j === i ? v : x)) })} />
                        ))}
                      </fieldset>
                    )}
                    {kpi.method === 'TASK_COUNT' && (
                      <div className="form-grid">
                        <TextField label="Completed tasks" type="number" min={0} value={form.completed} onChange={(v) => setForm({ ...form, completed: v })} disabled={!editable} />
                        <TextField label="Total tasks" type="number" min={1} value={form.total} onChange={(v) => setForm({ ...form, total: v })} disabled={!editable} />
                      </div>
                    )}
                    {kpi.method === 'TIMELINESS' && <TextField label="Actual completion date" type="date" value={form.actualDate} onChange={(v) => setForm({ ...form, actualDate: v })} disabled={!editable} />}
                    {kpi.method === 'QUALITY' && <TextField label="Quality measure" value={form.quality} onChange={(v) => setForm({ ...form, quality: v })} disabled={!editable} />}
                    {kpi.method === 'QUALITATIVE' && <TextArea label="Description of achievement" value={form.description} onChange={(v) => setForm({ ...form, description: v })} />}
                    {!['MILESTONE', 'TASK_COUNT'].includes(kpi.method) && (
                      <TextField label="Self-reported achievement (%)" type="number" min={0} max={100} value={form.selfProgress} onChange={(v) => setForm({ ...form, selfProgress: v })} disabled={!editable} />
                    )}
                    <div className="muted" style={{ fontSize: 12.5 }}>Progress from these entries: <b>{fmtPct(preview)}</b> · saved: <b>{fmtPct(kpi.selfProgress)}</b></div>
                    <TextArea label="Explanation for delayed or incomplete work" required={needsExplanation} value={form.explanation} onChange={(v) => setForm({ ...form, explanation: v })}
                      error={needsExplanation && !form.explanation.trim() ? 'Required: this KPI is behind its target' : undefined} />
                    <div className="row">
                      <input ref={fileRef} type="file" hidden onChange={(e) => e.target.files?.[0] && void upload(e.target.files[0])} />
                      <button className="btn btn-outline" disabled={!editable || busy} onClick={() => fileRef.current?.click()}><Upload /> Upload evidence</button>
                      <button className="btn btn-navy" disabled={!editable || busy} onClick={() => void save()}>Save progress</button>
                      <button className="btn btn-primary" disabled={!canSubmit || busy}
                        onClick={() => void run(() => api.post(`/appraisal/kpis/${kpi.id}/submit`), 'Submitted for verification')}>Submit for verification</button>
                    </div>
                    {['SUBMITTED', 'VERIFIED'].includes(kpi.progressStatus) && <Chip tone="info">{kpi.progressStatus === 'SUBMITTED' ? 'With your supervisor for verification' : 'Verified'}</Chip>}
                  </div>
                )}
              </Card>
              <div className="stack">
                <Card title="Evidence and verification" accent>
                  {!kpi || kpi.evidence.length === 0 ? <Empty text="No evidence uploaded for this KPI." /> : (
                    <table className="apr-table">
                      <thead><tr><th>File</th><th>Verification</th><th aria-label="Actions" /></tr></thead>
                      <tbody>{kpi.evidence.map((e) => (
                        <tr key={e.id}>
                          <td><button className="btn btn-ghost btn-sm" onClick={() => void download(`/appraisal/evidence/${e.id}/download`, { filename: e.fileName })}>{e.fileName}</button></td>
                          <td><Chip tone={e.status === 'VERIFIED' ? 'success' : e.status === 'RETURNED' ? 'danger' : undefined}>{e.status === 'PENDING' ? 'Pending' : e.status === 'VERIFIED' ? 'Verified' : 'Returned'}</Chip></td>
                          <td>{editable && e.status !== 'VERIFIED' && (
                            <button className="btn btn-ghost btn-sm" aria-label="Remove evidence" onClick={() => void run(() => api.delete(`/appraisal/evidence/${e.id}`), 'Evidence removed')}><Trash2 /></button>
                          )}</td>
                        </tr>
                      ))}</tbody>
                    </table>
                  )}
                </Card>
                <Card title="Submission history" accent>
                  {submissions.length === 0 ? <Empty text="Nothing submitted yet." /> : (
                    <table className="apr-table">
                      <thead><tr><th>KPI</th><th>Version</th><th>Date</th><th>Status</th></tr></thead>
                      <tbody>{submissions.map((s) => (
                        <tr key={`${s.kpiId}-${s.versionNo}`}><td>{s.title}</td><td>v{s.versionNo}</td><td>{fmtDayMonth(s.submittedAt)}</td>
                          <td>{s.status.charAt(0) + s.status.slice(1).toLowerCase()}</td></tr>
                      ))}</tbody>
                    </table>
                  )}
                </Card>
              </div>
            </div>
          )}
          <Note>Submit for verification is enabled after the required evidence is uploaded and every required field is complete. Method of the selected KPI: {kpi ? methodLabel(kpi.method) : '—'}.</Note>
        </>
      )}
    </AppraisalShell>
  );
}
