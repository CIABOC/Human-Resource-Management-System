import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Plus } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useToast } from '@/components/Toast';
import { Alert, Card, ConfirmDialog, Modal, PageHeader, SelectField, StatusBadge, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate, fmtDateTime } from '@/components/DataTable';
import type { IssuedDoc, LetterRequest, MyLetters } from './types';
import { LANG_LABEL } from './types';

/** Self-service letter and certificate requests (FR-EXT-003 / FR-EXT-005). */
export default function MyLettersPage() {
  const { t } = useTranslation();
  const nav = useNavigate();
  const toast = useToast();
  const { data, loading, error, reload } = useApi<MyLetters>('/extended/my-letters');
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ templateCode: '', language: 'en', purpose: '', addressee: '' });
  const [busy, setBusy] = useState(false);
  const [withdraw, setWithdraw] = useState<LetterRequest | null>(null);

  const option = data?.available.find((o) => o.code === form.templateCode);

  const submit = async () => {
    setBusy(true);
    try {
      await api.post('/extended/my-letters/requests', form);
      toast(data?.approvalRequired ? 'Request submitted for HR approval' : 'Request sent to HR', 'success');
      setOpen(false);
      setForm({ templateCode: '', language: 'en', purpose: '', addressee: '' });
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  const doWithdraw = async () => {
    if (!withdraw) return;
    setBusy(true);
    try {
      await api.post(`/extended/my-letters/requests/${withdraw.id}/withdraw`);
      toast('Request withdrawn', 'success');
      setWithdraw(null);
      void reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader title={t('extended.myLetters', 'My letters')} subtitle="Request service letters and certificates, and print the ones issued to you"
        actions={<button className="btn btn-gold" onClick={() => setOpen(true)} disabled={!data?.available.length}><Plus /> Request a letter</button>} />
      {error && <Alert kind="danger">{error}</Alert>}
      <div className="stack">
        <Card title="Issued documents" pad={false}>
          <DataTable<IssuedDoc>
            rows={data?.documents}
            loading={loading}
            empty="No documents have been issued to you yet"
            onRowClick={(d) => nav(`/extended/documents/${d.id}`)}
            columns={[
              { key: 'documentNo', header: 'Document no.', render: (d) => <span className="mono">{d.documentNo}</span> },
              { key: 'documentTypeName', header: 'Document' },
              { key: 'language', header: 'Language', render: (d) => LANG_LABEL[d.language] ?? d.language },
              { key: 'issuedAt', header: 'Issued', render: (d) => fmtDateTime(d.issuedAt) },
              { key: 'verificationCode', header: 'Verification code', render: (d) => <span className="mono">{d.verificationCode}</span> },
              { key: 'status', header: 'Status', render: (d) => <StatusBadge status={d.status === 'VALID' ? 'ACTIVE' : 'CANCELLED'} label={d.status === 'VALID' ? 'Valid' : 'Revoked'} /> },
            ]}
          />
        </Card>
        <Card title="My requests" pad={false}>
          <DataTable<LetterRequest>
            rows={data?.requests}
            loading={loading}
            empty="You have not requested any letters"
            columns={[
              { key: 'createdAt', header: 'Requested', render: (r) => fmtDate(r.createdAt) },
              { key: 'documentName', header: 'Document' },
              { key: 'purpose', header: 'Purpose' },
              { key: 'status', header: 'Status', render: (r) => <StatusBadge status={r.status} /> },
              { key: 'actions', header: '', render: (r) => (
                ['PENDING', 'RETURNED', 'APPROVED'].includes(r.status)
                  ? <button className="btn btn-ghost btn-sm" onClick={() => setWithdraw(r)}>Withdraw</button>
                  : r.issuedDocumentId ? <button className="btn btn-ghost btn-sm" onClick={() => nav(`/extended/documents/${r.issuedDocumentId}`)}>View</button> : null
              ) },
            ]}
          />
        </Card>
      </div>

      {open && (
        <Modal title="Request a letter or certificate" onClose={() => setOpen(false)} footer={
          <>
            <button className="btn btn-outline" onClick={() => setOpen(false)}>Cancel</button>
            <button className="btn btn-primary" onClick={() => void submit()} disabled={busy || !form.templateCode || !form.purpose.trim()}>Submit request</button>
          </>
        }>
          <div className="form-grid">
            <SelectField label="Document" required value={form.templateCode}
              onChange={(v) => setForm({ ...form, templateCode: v, language: 'en' })}
              options={(data?.available ?? []).map((o) => ({ value: o.code, label: o.name }))} />
            <SelectField label="Language" required value={form.language} placeholder={null}
              onChange={(v) => setForm({ ...form, language: v })}
              options={(option?.languages ?? ['en']).map((l) => ({ value: l, label: LANG_LABEL[l] ?? l }))} />
            <TextArea label="Purpose" required value={form.purpose} onChange={(v) => setForm({ ...form, purpose: v })}
              help="e.g. a housing loan application, visa application" />
            <TextField label="Addressed to (optional)" value={form.addressee} onChange={(v) => setForm({ ...form, addressee: v })} maxLength={300} />
          </div>
          {data?.approvalRequired && <Alert kind="info">Your request will be approved by HR before the document is issued.</Alert>}
        </Modal>
      )}
      {withdraw && (
        <ConfirmDialog title="Withdraw request" message={`Withdraw your request for a ${withdraw.documentName}?`} confirmLabel="Withdraw"
          danger busy={busy} onConfirm={() => void doWithdraw()} onCancel={() => setWithdraw(null)} />
      )}
    </>
  );
}
