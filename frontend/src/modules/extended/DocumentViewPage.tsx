import { useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Printer } from 'lucide-react';
import { api, errorMessage } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { Alert, Card, DL, Loading, PageHeader, StatusBadge } from '@/components/ui';
import { fmtDateTime } from '@/components/DataTable';
import type { IssuedDoc } from './types';
import { LANG_LABEL } from './types';

/** Printable view of an issued letter/certificate (rendered server-side; shown in a sandboxed frame). */
export default function DocumentViewPage() {
  const { id } = useParams();
  const { t } = useTranslation();
  const { data, error } = useApi<IssuedDoc>(`/extended/documents/${id}`);
  const [html, setHtml] = useState<string | null>(null);
  const [htmlError, setHtmlError] = useState<string | null>(null);
  const frame = useRef<HTMLIFrameElement>(null);

  useEffect(() => {
    api.get<string>(`/extended/documents/${id}/print`, { responseType: 'text', transformResponse: (r) => r })
      .then((r) => setHtml(r.data))
      .catch((e) => setHtmlError(errorMessage(e)));
  }, [id]);

  const print = () => frame.current?.contentWindow?.print();

  return (
    <>
      <PageHeader eyebrow={data?.documentNo} title={data?.documentTypeName ?? t('extended.document', 'Issued document')}
        subtitle={data ? `${data.employee.employeeNo} — ${data.employee.nameWithInitials}` : undefined}
        actions={<button className="btn btn-gold" onClick={print} disabled={!html}><Printer /> Print</button>} />
      {(error || htmlError) && <Alert kind="danger">{error ?? htmlError}</Alert>}
      {data?.status === 'REVOKED' && <Alert kind="warning">This document was revoked on {fmtDateTime(data.revokedAt)}: {data.revokeReason}</Alert>}
      <div className="grid" style={{ gridTemplateColumns: 'minmax(0, 3fr) minmax(240px, 1fr)', alignItems: 'start' }}>
        <Card pad={false}>
          {html === null ? <Loading /> : (
            <iframe ref={frame} title="Document" srcDoc={html} sandbox="allow-same-origin allow-modals"
              style={{ width: '100%', height: '1100px', border: 0, background: '#fff' }} />
          )}
        </Card>
        {data && (
          <Card title="Details">
            <DL items={[
              ['Status', <StatusBadge key="s" status={data.status === 'VALID' ? 'ACTIVE' : 'CANCELLED'} label={data.status === 'VALID' ? 'Valid' : 'Revoked'} />],
              ['Document no.', <span key="n" className="mono">{data.documentNo}</span>],
              ['Language', LANG_LABEL[data.language] ?? data.language],
              ['Issued', fmtDateTime(data.issuedAt)],
              ['Issued by', data.issuedBy],
              ['Verification code', <span key="c" className="mono">{data.verificationCode}</span>],
            ]} />
            <p className="muted" style={{ fontSize: 12.5, marginTop: 12 }}>
              Third parties can confirm this document at /api/public/verify/&lt;code&gt;/page. Only the document type, issue date,
              holder&apos;s name with initials and validity are disclosed.
            </p>
          </Card>
        )}
      </div>
    </>
  );
}
