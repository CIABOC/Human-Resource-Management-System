import { useState } from 'react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Card, Empty, Loading, Modal, SelectField, TextArea, TextField } from '@/components/ui';
import type { Member } from './types';
import { AppraisalShell, Chip, fmtDayMonth, Note, useAprContext } from './shared';

interface Checkin { id: number; employeeId: number; employeeName?: string; supervisorName?: string; date: string; progressDiscussed?: string; agreedActions?: string; status: string; canUpdate: boolean }
interface Fb { id: number; date: string; fromName?: string; toName?: string; type: string; body: string; status: string; mine: boolean }
interface Lists { history: Fb[]; requests: Fb[]; supervisorUserId?: number | null; supervisorName?: string | null }

/** Screen 13: check-ins between supervisor and employee, feedback, and feedback requests. */
export default function FeedbackPage() {
  return <AppraisalShell title="Check-ins and feedback"><Body /></AppraisalShell>;
}

function Body() {
  const toast = useToast();
  const { ctx } = useAprContext();
  const isSup = !!(ctx?.supervisor || ctx?.manage);
  const checkins = useApi<Checkin[]>('/appraisal/checkins');
  const fb = useApi<Lists>('/appraisal/feedback');
  const members = useApi<Member[]>(isSup ? '/appraisal/team/members' : null);
  const [banner, setBanner] = useState<string | null>(null);
  const [schedule, setSchedule] = useState(false);
  const [record, setRecord] = useState<Checkin | null>(null);
  const [form, setForm] = useState({ employeeId: '', date: '', progress: '', actions: '' });
  const [give, setGive] = useState({ employeeId: '', type: 'RECOGNITION', body: '', requestId: '' });
  const [request, setRequest] = useState('');
  const [busy, setBusy] = useState(false);

  const run = async (fn: () => Promise<unknown>, ok: string, after?: () => void) => {
    setBusy(true);
    try {
      await fn();
      toast(ok, 'success');
      after?.();
      void checkins.reload();
      void fb.reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };
  const memberOptions = (members.data ?? []).map((m) => ({ value: String(m.employeeId), label: `${m.name} (${m.employeeNo})` }));

  return (
    <>
      {banner && <Note kind="success">{banner}</Note>}
      {(checkins.error || fb.error) && <Alert kind="danger">{checkins.error ?? fb.error}</Alert>}
      <div className="apr-split">
        <Card title="Check-ins" accent>
          {checkins.loading && !checkins.data ? <Loading /> : !checkins.data?.length ? <Empty text="No check-ins yet." /> : (
            <div className="apr-table-wrap">
              <table className="apr-table">
                <thead><tr><th>Date</th>{isSup && <th>Employee</th>}<th>Progress discussed</th><th>Agreed actions</th><th>Status</th></tr></thead>
                <tbody>{checkins.data.map((c) => (
                  <tr key={c.id}>
                    <td>{fmtDayMonth(c.date)}</td>{isSup && <td>{c.employeeName}</td>}
                    <td>{c.progressDiscussed ?? '—'}</td><td>{c.agreedActions ?? '—'}</td>
                    <td>{c.canUpdate ? <button className="btn btn-ghost btn-sm" onClick={() => { setRecord(c); setForm({ ...form, progress: c.progressDiscussed ?? '', actions: c.agreedActions ?? '' }); }}>Record</button>
                      : <Chip tone={c.status === 'DONE' ? 'success' : undefined}>{c.status === 'DONE' ? 'Done' : 'Scheduled'}</Chip>}</td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
          )}
          {isSup && <button className="btn btn-navy btn-sm" style={{ marginTop: 12 }} onClick={() => setSchedule(true)}>Schedule check-in</button>}
        </Card>
        {isSup ? (
          <Card title="Give feedback" accent>
            <div className="stack" style={{ gap: 10 }}>
              <SelectField label="Employee" value={give.employeeId} onChange={(v) => setGive({ ...give, employeeId: v })} options={memberOptions} />
              <SelectField label="Type" value={give.type} onChange={(v) => setGive({ ...give, type: v })} placeholder=""
                options={[{ value: 'RECOGNITION', label: 'Recognition' }, { value: 'IMPROVEMENT', label: 'Improvement' }]} />
              <TextArea label="Feedback" rows={4} value={give.body} onChange={(v) => setGive({ ...give, body: v })} />
              <div><button className="btn btn-navy btn-sm" disabled={busy || !give.employeeId || !give.body.trim()} onClick={() => void run(
                () => api.post('/appraisal/feedback', { toEmployeeId: Number(give.employeeId), type: give.type, body: give.body, requestId: give.requestId ? Number(give.requestId) : null }),
                'Feedback sent', () => setGive({ employeeId: '', type: 'RECOGNITION', body: '', requestId: '' }))}>Send</button></div>
            </div>
          </Card>
        ) : (
          <Card title="Ask for feedback" accent>
            {fb.data?.supervisorUserId ? <>
              <p className="muted" style={{ marginTop: 0 }}>Your request goes to {fb.data.supervisorName}.</p>
              <TextArea label="What would you like feedback on?" rows={4} value={request} onChange={setRequest} />
              <button className="btn btn-navy btn-sm" style={{ marginTop: 10 }} disabled={busy || !request.trim()}
                onClick={() => void run(() => api.post('/appraisal/feedback', { type: 'REQUEST', body: request }), 'Feedback requested', () => setRequest(''))}>Send request</button>
            </> : <Empty text="No supervisor is assigned to your appraisal yet." />}
          </Card>
        )}
      </div>
      {ctx?.employeeId && isSup && fb.data?.supervisorUserId && (
        <Card title="Ask your own supervisor for feedback">
          <div className="row" style={{ alignItems: 'flex-end' }}>
            <div style={{ flex: 1 }}><TextField label={`Request to ${fb.data.supervisorName}`} value={request} onChange={setRequest} /></div>
            <button className="btn btn-outline btn-sm" disabled={busy || !request.trim()}
              onClick={() => void run(() => api.post('/appraisal/feedback', { type: 'REQUEST', body: request }), 'Feedback requested', () => setRequest(''))}>Send request</button>
          </div>
        </Card>
      )}
      <Card title="Feedback history" accent>
        {!fb.data?.history.length ? <Empty text="No feedback yet." /> : (
          <div className="apr-table-wrap">
            <table className="apr-table">
              <thead><tr><th>Date</th><th>From</th><th>To</th><th>Type</th><th>Summary</th></tr></thead>
              <tbody>{fb.data.history.map((f) => (
                <tr key={f.id}><td>{fmtDayMonth(f.date)}</td><td>{f.fromName}</td><td>{f.toName}</td>
                  <td>{f.type === 'REQUEST' ? 'Request' : f.type === 'RECOGNITION' ? 'Recognition' : 'Improvement'}</td><td>{f.body}</td></tr>
              ))}</tbody>
            </table>
          </div>
        )}
      </Card>
      {isSup && (
        <Card title="Feedback requests" accent>
          {!fb.data?.requests.length ? <div className="apr-empty-dashed">No feedback requests yet. Requests from employees will appear here.</div> : (
            <table className="apr-table">
              <thead><tr><th>Date</th><th>From</th><th>Request</th><th>Status</th></tr></thead>
              <tbody>{fb.data.requests.map((f) => (
                <tr key={f.id}><td>{fmtDayMonth(f.date)}</td><td>{f.fromName}</td><td>{f.body}</td>
                  <td>{f.status === 'OPEN' ? <button className="btn btn-ghost btn-sm" onClick={() => {
                    const m = (members.data ?? []).find((x) => x.name === f.fromName);
                    setGive({ employeeId: m ? String(m.employeeId) : '', type: 'RECOGNITION', body: '', requestId: String(f.id) });
                  }}>Respond</button> : <Chip tone="success">Answered</Chip>}</td></tr>
              ))}</tbody>
            </table>
          )}
        </Card>
      )}
      {schedule && (
        <Modal title="Schedule check-in" onClose={() => setSchedule(false)} footer={<>
          <button className="btn btn-ghost" onClick={() => setSchedule(false)}>Cancel</button>
          <button className="btn btn-navy" disabled={busy || !form.employeeId || !form.date} onClick={() => void run(
            () => api.post('/appraisal/checkins', { employeeId: Number(form.employeeId), date: form.date, progressDiscussed: form.progress, agreedActions: form.actions }),
            'Check-in scheduled', () => {
              const name = memberOptions.find((m) => m.value === form.employeeId)?.label ?? 'the employee';
              setBanner(`Check-in scheduled for ${fmtDayMonth(form.date)}. ${name} has been notified.`);
              setSchedule(false);
            })}>Schedule</button></>}>
          <div className="stack" style={{ gap: 10 }}>
            <SelectField label="Employee" required value={form.employeeId} onChange={(v) => setForm({ ...form, employeeId: v })} options={memberOptions} />
            <TextField label="Date" type="date" required value={form.date} onChange={(v) => setForm({ ...form, date: v })} />
            <TextArea label="Topics to discuss (optional)" value={form.progress} onChange={(v) => setForm({ ...form, progress: v })} />
          </div>
        </Modal>
      )}
      {record && (
        <Modal title={`Record check-in with ${record.employeeName}`} onClose={() => setRecord(null)} footer={<>
          <button className="btn btn-ghost" onClick={() => setRecord(null)}>Cancel</button>
          <button className="btn btn-navy" disabled={busy || !form.progress.trim()} onClick={() => void run(
            () => api.post(`/appraisal/checkins/${record.id}/done`, { progressDiscussed: form.progress, agreedActions: form.actions }), 'Check-in recorded', () => setRecord(null))}>Save</button></>}>
          <div className="stack" style={{ gap: 10 }}>
            <TextArea label="Progress discussed" required value={form.progress} onChange={(v) => setForm({ ...form, progress: v })} />
            <TextArea label="Agreed actions" value={form.actions} onChange={(v) => setForm({ ...form, actions: v })} />
          </div>
        </Modal>
      )}
    </>
  );
}
