import { useTranslation } from 'react-i18next';
import { useApi } from '@/hooks/useApi';
import { Card, Empty, Loading, PageHeader } from '@/components/ui';
import { fmtDate } from '@/components/DataTable';
import type { Announcement, AvailablePosition } from './types';

/** Available positions and published recruitment announcements, visible to all staff (FR-REC-002, -003). */
export default function PositionsPage() {
  const { t } = useTranslation();
  const positions = useApi<AvailablePosition[]>('/recruitment/positions');
  const notices = useApi<Announcement[]>('/recruitment/announcements/published');

  return (
    <>
      <PageHeader title={t('rec.positions', 'Available positions')} subtitle="Open vacancies and recruitment announcements" />
      <div className="grid grid-2">
        <Card title="Open positions">
          {positions.loading && <Loading />}
          {positions.data?.length === 0 && <Empty text="There are no open positions at present." />}
          <div className="stack">
            {positions.data?.map((p) => (
              <div key={p.id} className="card card-pad">
                <div className="row between"><strong>{p.title}</strong><span className="badge badge-info">{p.numPosts} post(s)</span></div>
                <div className="muted">{p.designationName} · {p.orgUnitName} · Ref. {p.referenceNo}</div>
                {p.closingDate && <div className="faint">Closes {fmtDate(p.closingDate)}</div>}
                {p.description && <p style={{ whiteSpace: 'pre-wrap' }}>{p.description}</p>}
                {p.requirements && <p style={{ whiteSpace: 'pre-wrap' }}><strong>Requirements: </strong>{p.requirements}</p>}
              </div>
            ))}
          </div>
        </Card>
        <Card title="Announcements">
          {notices.loading && <Loading />}
          {notices.data?.length === 0 && <Empty text="No current announcements." />}
          <div className="stack">
            {notices.data?.map((a) => (
              <div key={a.id} className="card card-pad">
                <strong>{a.title}</strong>
                <div className="faint">Published {fmtDate(a.publishDate)}{a.closingDate ? ` · closes ${fmtDate(a.closingDate)}` : ''}</div>
                <p style={{ whiteSpace: 'pre-wrap' }}>{a.content}</p>
              </div>
            ))}
          </div>
        </Card>
      </div>
    </>
  );
}
