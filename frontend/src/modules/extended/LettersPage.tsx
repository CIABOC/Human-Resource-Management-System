import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { FileSignature } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, Modal, PageHeader, SelectField, StatusBadge, Tabs, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate, fmtDateTime } from '@/components/DataTable';
import { EmployeeSelect, employeeLabel } from '@/components/pickers';
import type { IssuedDoc, LetterRequest, TemplateOption } from './types';
import { LANG_LABEL, titleCase } from './types';

type Tab = 'requests' | 'register';

export default function LettersPage() {
  const { t } = useTranslation();
  const { can } = useAuth();
  const [tab, setTab] = useState<Tab>('requests');
  const [issue, setIssue] = useState<{ request?: LetterRequest } | null>(null);
  const [version, setVersion] = useState(0);
  return (
    <>
      <PageHeader title={t('extended.letters', 'Letters & certificates')}
        subtitle="Approve and issue employee requests, issue certificates and maintain the issued-document register"
        actions={can('EXT_MANAGE') && <button className="btn btn-gold" onClick={() => setIssue({})}><FileSignature /> Issue a document</button>} />
      <Tabs<Tab> value={tab} onChange={setTab} tabs={[{ key: 'requests', label: 'Requests' }, { key: 'register', label: 'Issued register' }]} />
      <div style={{ marginTop: 16 }}>
        {tab === 'requests' ? <Requests key={version} onIssue={(r) => setIssue({ request: r })} /> : <Register key={version} />}
      </div>
      {issue && <IssueModal request={issue.request} onClose={() => setIssue(null)} onIssued={() => { setIssue(null); setVersion((v) => v + 1); setTab('register'); }} />}
    </>
  );
}

function Requests({ onIssue }: { onIssue: (r: LetterRequest) => void }) {
  const { can } = useAuth();
  const [status, setStatus] = useState('');
  const { data, loading, error } = useApi<LetterRequest[]>('/extended/letters/requests', { status });
  return (
    <>
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false} title={
        <div className="filters">
          <SelectField label="Status" value={status} onChange={setStatus} placeholder="All"
            options={['PENDING', 'APPROVED', 'RETURNED', 'ISSUED', 'REJECTED', 'CANCELLED'].map((s) => ({ value: s, label: titleCase(s) }))} />
        </div>
      }>
        <DataTable<LetterRequest>
          rows={data}
          loading={loading}
          columns={[
            { key: 'createdAt', header: 'Requested', render: (r) => fmtDate(r.createdAt) },
            { key: 'employee', header: 'Employee', render: (r) => employeeLabel(r.employee) },
            { key: 'documentName', header: 'Document' },
            { key: 'language', header: 'Language', render: (r) => LANG_LABEL[r.language] ?? r.language },
            { key: 'purpose', header: 'Purpose' },
            { key: 'status', header: 'Status', render: (r) => <StatusBadge status={r.status} label={r.status === 'APPROVED' ? 'Ready to issue' : undefined} /> },
            { key: 'actions', header: '', render: (r) => (
              r.status === 'PENDING' && r.workflowInstanceId ? <Link className="btn btn-ghost btn-sm" to={`/approvals/${r.workflowInstanceId}`}>Approve</Link>
                : r.status === 'APPROVED' && can('EXT_MANAGE') ? <button className="btn btn-primary btn-sm" onClick={() => onIssue(r)}>Issue</button>
                  : r.issuedDocumentId ? <Link className="btn btn-ghost btn-sm" to={`/extended/documents/${r.issuedDocumentId}`}>View</Link> : null
            ) },
          ]}
        />
      </Card>
    </>
  );
}

function Register() {
  const { can } = useAuth();
  const nav = useNavigate();
  const toast = useToast();
  const [status, setStatus] = useState('');
  const { data, loading, error, reload } = useApi<IssuedDoc[]>('/extended/letters/documents', { status });
  const [revoke, setRevoke] = useState<IssuedDoc | null>(null);
  const [reason, setReason] = useState('');
  const doRevoke = async () => {
    if (!revoke) return;
    try {
      await api.post(`/extended/letters/documents/${revoke.id}/revoke`, { reason });
      toast('Document revoked; public verification now reports it as revoked', 'success');
      setRevoke(null);
      setReason('');
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };
  return (
    <>
      {error && <Alert kind="danger">{error}</Alert>}
      <Card pad={false} title={
        <div className="filters">
          <SelectField label="Status" value={status} onChange={setStatus} placeholder="All"
            options={[{ value: 'VALID', label: 'Valid' }, { value: 'REVOKED', label: 'Revoked' }]} />
        </div>
      }>
        <DataTable<IssuedDoc>
          rows={data}
          loading={loading}
          onRowClick={(d) => nav(`/extended/documents/${d.id}`)}
          columns={[
            { key: 'documentNo', header: 'Document no.', render: (d) => <span className="mono">{d.documentNo}</span> },
            { key: 'issuedAt', header: 'Issued', render: (d) => fmtDateTime(d.issuedAt) },
            { key: 'employee', header: 'Issued to', render: (d) => employeeLabel(d.employee) },
            { key: 'documentTypeName', header: 'Document' },
            { key: 'issuedBy', header: 'Issued by' },
            { key: 'status', header: 'Status', render: (d) => <StatusBadge status={d.status === 'VALID' ? 'ACTIVE' : 'CANCELLED'} label={d.status === 'VALID' ? 'Valid' : 'Revoked'} /> },
            { key: 'actions', header: '', render: (d) => d.status === 'VALID' && can('EXT_MANAGE')
              ? <button className="btn btn-ghost btn-sm" onClick={(e) => { e.stopPropagation(); setRevoke(d); }}>Revoke</button> : null },
          ]}
        />
      </Card>
      {revoke && (
        <Modal title={`Revoke ${revoke.documentNo}`} onClose={() => setRevoke(null)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setRevoke(null)}>Cancel</button>
            <button className="btn btn-danger" disabled={!reason.trim()} onClick={() => void doRevoke()}>Revoke</button>
          </>
        }>
          <TextArea label="Reason" required value={reason} onChange={setReason} />
        </Modal>
      )}
    </>
  );
}

function IssueModal({ request, onClose, onIssued }: { request?: LetterRequest; onClose: () => void; onIssued: () => void }) {
  const toast = useToast();
  const { data: options } = useApi<TemplateOption[]>(request ? null : '/extended/letters/options');
  const [f, setF] = useState({
    employeeId: request ? String(request.employee.id) : '',
    templateCode: request?.templateCode ?? '',
    language: request?.language ?? 'en',
    purpose: request?.purpose ?? '',
    addressee: request?.addressee ?? '',
  });
  const [fields, setFields] = useState<Record<string, string>>({});
  const [preview, setPreview] = useState<{ title: string; bodyHtml: string; missingFields: string[] } | null>(null);
  const [busy, setBusy] = useState(false);
  const option = options?.find((o) => o.code === f.templateCode);
  const body = () => ({
    requestId: request?.id ?? null,
    employeeId: f.employeeId ? Number(f.employeeId) : null,
    templateCode: f.templateCode,
    language: f.language,
    purpose: f.purpose,
    addressee: f.addressee,
    fields,
  });

  useEffect(() => {
    if (!f.employeeId || !f.templateCode) {
      setPreview(null);
      return;
    }
    const h = setTimeout(() => {
      api.post('/extended/letters/preview', body()).then((r) => setPreview(r.data)).catch(() => setPreview(null));
    }, 400);
    return () => clearTimeout(h);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [f, fields]);

  const required = Array.from(new Set([...(request?.requiredFields ?? []), ...(preview?.missingFields ?? []), ...Object.keys(fields)]));

  const issue = async () => {
    setBusy(true);
    try {
      await api.post('/extended/letters/issue', body());
      toast('Document issued with a verification code; the employee has been notified', 'success');
      onIssued();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal wide title={request ? `Issue ${request.documentName} — ${employeeLabel(request.employee)}` : 'Issue a letter or certificate'} onClose={onClose} footer={
      <>
        <button className="btn btn-outline" onClick={onClose}>Cancel</button>
        <button className="btn btn-gold" disabled={busy || !f.employeeId || !f.templateCode || (preview?.missingFields.length ?? 0) > 0} onClick={() => void issue()}>Issue document</button>
      </>
    }>
      <div className="form-grid">
        {!request && (
          <>
            <EmployeeSelect required value={f.employeeId} onChange={(v) => setF({ ...f, employeeId: v })} />
            <SelectField label="Document" required value={f.templateCode} onChange={(v) => setF({ ...f, templateCode: v, language: 'en' })}
              options={(options ?? []).map((o) => ({ value: o.code, label: `${o.name} (${titleCase(o.documentKind)})` }))} />
            <SelectField label="Language" required value={f.language} placeholder={null} onChange={(v) => setF({ ...f, language: v })}
              options={(option?.languages ?? ['en']).map((l) => ({ value: l, label: LANG_LABEL[l] ?? l }))} />
            <TextField label="Purpose" value={f.purpose} onChange={(v) => setF({ ...f, purpose: v })} />
            <TextField label="Addressed to" value={f.addressee} onChange={(v) => setF({ ...f, addressee: v })} />
          </>
        )}
        {required.map((k) => (
          <TextField key={k} label={`${titleCase(k.replace(/([A-Z])/g, '_$1'))} (required by template)`} required value={fields[k] ?? ''}
            onChange={(v) => setFields({ ...fields, [k]: v })} />
        ))}
      </div>
      {preview && (
        <Card title={`Preview: ${preview.title}`}>
          <iframe title="Preview" sandbox="" srcDoc={`<!doctype html><meta charset="utf-8"><body style="font-family:Georgia,serif;font-size:14px;line-height:1.6;color:#0b1f3a">${preview.bodyHtml}</body>`}
            style={{ width: '100%', height: 260, border: 0 }} />
        </Card>
      )}
    </Modal>
  );
}
