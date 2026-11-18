import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { BadgeCheck, Download, FilePlus2, History, Pencil, Plus, Scissors } from 'lucide-react';
import { api, download, errorMessage } from '@/api/client';
import type { Designation, MasterDataItem } from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { useAuth } from '@/auth/AuthContext';
import { useToast } from '@/components/Toast';
import { Alert, Card, DL, Loading, Modal, PageHeader, SelectField, StatusBadge, Tabs, TextArea, TextField } from '@/components/ui';
import { DataTable, fmtDate, fmtDateTime } from '@/components/DataTable';
import { Amendment, LegalDocument, LegalSection } from './types';
import { DocumentModal } from './LegalListPage';

type Tab = 'provisions' | 'amendments' | 'versions';
const LEVELS = ['PART', 'CHAPTER', 'SECTION', 'SUBSECTION', 'CLAUSE'] as const;

export default function LegalDetailPage() {
  const { id } = useParams();
  const nav = useNavigate();
  const { can } = useAuth();
  const toast = useToast();
  const manage = can('LEGAL_MANAGE');
  const doc = useApi<LegalDocument>(`/legal/documents/${id}`);
  const [tab, setTab] = useState<Tab>('provisions');
  const [asOf, setAsOf] = useState(new Date().toISOString().slice(0, 10));
  const sections = useApi<LegalSection[]>(`/legal/documents/${id}/sections`, { asOf });
  const amendments = useApi<Amendment[]>(`/legal/documents/${id}/amendments`);
  const versions = useApi<LegalDocument[]>(`/legal/documents/${id}/versions`);
  const designations = useApi<Designation[]>('/establishment/designations');
  const topics = useApi<MasterDataItem[]>('/master-data/LEGAL_TOPIC');
  const [modal, setModal] = useState<null | 'edit' | 'version' | 'section' | 'amend'>(null);
  const [editing, setEditing] = useState<LegalSection | null>(null);
  const [amendTarget, setAmendTarget] = useState<LegalSection | null>(null);
  const [versionsOf, setVersionsOf] = useState<LegalSection | null>(null);

  const d = doc.data;
  const desName = useMemo(() => new Map((designations.data ?? []).map((x) => [x.id, x.name])), [designations.data]);
  const depth = useMemo(() => {
    const byKey = new Map((sections.data ?? []).map((s) => [s.sectionKey, s]));
    return (s: LegalSection) => {
      let n = 0;
      let p = s.parentKey ? byKey.get(s.parentKey) : undefined;
      while (p && n < 6) { n++; p = p.parentKey ? byKey.get(p.parentKey) : undefined; }
      return n;
    };
  }, [sections.data]);

  if (doc.loading && !d) return <Loading />;
  if (doc.error) return <Alert kind="danger">{doc.error}</Alert>;
  if (!d) return null;

  const act = async (fn: () => Promise<unknown>, ok: string) => {
    try {
      await fn();
      toast(ok, 'success');
      void doc.reload();
      void sections.reload();
      void amendments.reload();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  return (
    <>
      <PageHeader eyebrow={`${d.docType} · ${d.docNumber} · version ${d.versionNo}`} title={d.title}
        subtitle={<span className="row"><StatusBadge status={d.status} /><StatusBadge status={d.verificationStatus} label={d.verificationStatus === 'VERIFIED' ? 'Verified against official document' : 'Draft — not yet verified'} /></span>}
        actions={
          <>
            {d.fileId && <button className="btn btn-outline" onClick={() => void download(`/legal/documents/${d.id}/file`)}><Download /> Download</button>}
            {manage && d.status !== 'SUPERSEDED' && (
              <>
                <button className="btn btn-outline" onClick={() => setModal('edit')}><Pencil /> Edit</button>
                <button className="btn btn-outline" onClick={() => setModal('version')}><FilePlus2 /> New version</button>
                {d.verificationStatus !== 'VERIFIED' && <button className="btn btn-gold" onClick={() => void act(() => api.post(`/legal/documents/${d.id}/verify`), 'Document verified')}><BadgeCheck /> Mark verified</button>}
              </>
            )}
          </>
        } />
      {d.verificationStatus !== 'VERIFIED' && <div style={{ marginBottom: 16 }}><Alert kind="warning">This content is a draft. It is treated as approved only after being verified against the official document (FR-EST-044).</Alert></div>}

      <div className="grid grid-3" style={{ marginBottom: 18 }}>
        <Card title="Details" className="" accent>
          <DL items={[
            ['Issuing authority', d.issuingAuthority],
            ['Published', fmtDate(d.publicationDate)],
            ['Effective', fmtDate(d.effectiveDate)],
            ['File', d.fileName],
            ['Verified by', d.verifiedBy ? `${d.verifiedBy}, ${fmtDateTime(d.verifiedAt)}` : ''],
          ]} />
          {d.description && <p className="muted" style={{ marginTop: 12 }}>{d.description}</p>}
          {manage && (
            <div className="row" style={{ marginTop: 14 }}>
              {d.status === 'ACTIVE' && <button className="btn btn-outline btn-sm" onClick={() => void act(() => api.post(`/legal/documents/${d.id}/status?status=ARCHIVED`), 'Archived')}>Archive</button>}
              {d.status === 'ARCHIVED' && <button className="btn btn-outline btn-sm" onClick={() => void act(() => api.post(`/legal/documents/${d.id}/status?status=ACTIVE`), 'Activated')}>Activate</button>}
            </div>
          )}
        </Card>
        <div style={{ gridColumn: 'span 2' }}>
          <Tabs tabs={[{ key: 'provisions', label: 'Provisions' }, { key: 'amendments', label: 'Amendments' }, { key: 'versions', label: 'Document versions' }]} value={tab} onChange={setTab} />
          {tab === 'provisions' && (
            <Card pad={false} title={<TextField label="Provisions in force on" type="date" value={asOf} onChange={setAsOf} />}
              actions={manage ? <button className="btn btn-gold btn-sm" onClick={() => { setEditing(null); setModal('section'); }}><Plus /> Add provision</button> : undefined}>
              <DataTable<LegalSection> rows={sections.data} loading={sections.loading} columns={[
                { key: 'number', header: 'Provision', render: (s) => (
                  <div style={{ paddingLeft: depth(s) * 18 }}>
                    <span className="badge badge-neutral" style={{ marginRight: 6 }}>{s.level.toLowerCase()} {s.number}</span>
                    <strong>{s.heading}</strong>
                    {s.content && <div className="muted" style={{ fontSize: 12.5, marginTop: 4, whiteSpace: 'pre-wrap' }}>{s.content.length > 280 ? s.content.slice(0, 280) + '…' : s.content}</div>}
                    {(s.tags.length > 0 || s.designationIds.length > 0) && (
                      <div className="row" style={{ marginTop: 4, gap: 4 }}>
                        {s.tags.map((t) => <span key={t} className="badge badge-gold">{topics.data?.find((x) => x.code === t)?.nameEn ?? t}</span>)}
                        {s.designationIds.map((x) => <span key={x} className="badge badge-info">{desName.get(x) ?? x}</span>)}
                      </div>
                    )}
                  </div>
                ) },
                { key: 'effectiveFrom', header: 'In force', render: (s) => <span className="nowrap">{fmtDate(s.effectiveFrom)}{s.versionNo > 1 ? ` · v${s.versionNo}` : ''}</span> },
                { key: 'verificationStatus', header: '', render: (s) => <StatusBadge status={s.verificationStatus} /> },
                { key: 'a', header: '', render: (s) => (
                  <div className="row" style={{ gap: 2 }}>
                    <button className="btn btn-ghost btn-sm" title="Versions" aria-label="Versions" onClick={() => setVersionsOf(s)}><History /></button>
                    {manage && <button className="btn btn-ghost btn-sm" title="Edit / classify" aria-label="Edit" onClick={() => { setEditing(s); setModal('section'); }}><Pencil /></button>}
                    {manage && s.verificationStatus !== 'VERIFIED' && <button className="btn btn-ghost btn-sm" title="Verify" aria-label="Verify" onClick={() => void act(() => api.post(`/legal/sections/${s.id}/verify`), 'Provision verified')}><BadgeCheck /></button>}
                    {manage && <button className="btn btn-ghost btn-sm" title="Record amendment" aria-label="Record amendment" onClick={() => { setAmendTarget(s); setModal('amend'); }}><Scissors /></button>}
                  </div>
                ) },
              ]} />
            </Card>
          )}
          {tab === 'amendments' && (
            <Card pad={false} actions={manage ? <button className="btn btn-gold btn-sm" onClick={() => { setAmendTarget(null); setModal('amend'); }}><Plus /> Record insertion</button> : undefined} title="Amendment history">
              <DataTable<Amendment> rows={amendments.data} loading={amendments.loading} columns={[
                { key: 'effectiveDate', header: 'Effective', render: (a) => fmtDate(a.effectiveDate) },
                { key: 'amendmentType', header: 'Type', render: (a) => <span className="badge badge-gold">{a.amendmentType.toLowerCase()}</span> },
                { key: 'description', header: 'Description' },
                { key: 'amendingDocumentId', header: 'Amending instrument', render: (a) => (a.amendingDocumentId ? <Link to={`/legal/${a.amendingDocumentId}`}>Document #{a.amendingDocumentId}</Link> : '—') },
              ]} />
            </Card>
          )}
          {tab === 'versions' && (
            <Card pad={false}>
              <DataTable<LegalDocument> rows={versions.data} loading={versions.loading} onRowClick={(v) => nav(`/legal/${v.id}`)} columns={[
                { key: 'versionNo', header: 'Version', render: (v) => `v${v.versionNo}` },
                { key: 'title', header: 'Title' },
                { key: 'fileName', header: 'File' },
                { key: 'createdAt', header: 'Uploaded', render: (v) => fmtDateTime((v as unknown as { createdAt: string }).createdAt) },
                { key: 'status', header: 'Status', render: (v) => <StatusBadge status={v.status} /> },
              ]} />
            </Card>
          )}
        </div>
      </div>

      {(modal === 'edit' || modal === 'version') && (
        <DocumentModal existing={d} mode={modal} onClose={() => setModal(null)} onSaved={(nd) => { if (modal === 'version') nav(`/legal/${nd.id}`); else void doc.reload(); }} />
      )}
      {modal === 'section' && (
        <SectionModal docId={d.id} existing={editing} sections={sections.data ?? []} designations={designations.data ?? []} topics={topics.data ?? []}
          onClose={() => setModal(null)} onSaved={() => void sections.reload()} />
      )}
      {modal === 'amend' && (
        <AmendModal doc={d} section={amendTarget} sections={sections.data ?? []} onClose={() => setModal(null)} onSaved={() => { void sections.reload(); void amendments.reload(); }} />
      )}
      {versionsOf && <SectionVersions section={versionsOf} onClose={() => setVersionsOf(null)} />}
    </>
  );
}

function SectionModal({ docId, existing, sections, designations, topics, onClose, onSaved }: {
  docId: number; existing: LegalSection | null; sections: LegalSection[]; designations: Designation[]; topics: MasterDataItem[];
  onClose: () => void; onSaved: () => void;
}) {
  const toast = useToast();
  const verified = existing?.verificationStatus === 'VERIFIED';
  const [f, setF] = useState({
    parentKey: existing?.parentKey ?? '', level: existing?.level ?? 'SECTION', number: existing?.number ?? '', heading: existing?.heading ?? '',
    content: existing?.content ?? '', sortOrder: String(existing?.sortOrder ?? 0), effectiveFrom: existing?.effectiveFrom ?? '',
  });
  const [tags, setTags] = useState<string[]>(existing?.tags ?? []);
  const [des, setDes] = useState<number[]>(existing?.designationIds ?? []);
  const set = (k: keyof typeof f) => (v: string) => setF({ ...f, [k]: v });
  const toggle = <T,>(arr: T[], v: T) => (arr.includes(v) ? arr.filter((x) => x !== v) : [...arr, v]);

  const save = async () => {
    const body = { ...f, parentKey: f.parentKey || null, sortOrder: Number(f.sortOrder || 0), effectiveFrom: f.effectiveFrom || null, tags, designationIds: des };
    try {
      if (existing) await api.put(`/legal/sections/${existing.id}`, body);
      else await api.post(`/legal/documents/${docId}/sections`, body);
      toast('Provision saved', 'success');
      onSaved();
      onClose();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  return (
    <Modal title={existing ? `Provision ${existing.number}` : 'Add provision'} onClose={onClose} wide footer={
      <>
        <button className="btn btn-outline" onClick={onClose}>Cancel</button>
        <button className="btn btn-primary" onClick={() => void save()}>Save</button>
      </>
    }>
      {verified && <div style={{ marginBottom: 12 }}><Alert kind="info">This provision is verified. Its text can only change through an amendment; you can still update its topics and linked designations.</Alert></div>}
      <div className="form-grid cols-3">
        <SelectField label="Level" required value={f.level} onChange={set('level')} placeholder={null} disabled={verified} options={LEVELS.map((l) => ({ value: l, label: l.toLowerCase() }))} />
        <TextField label="Number" required value={f.number} onChange={set('number')} disabled={verified} />
        <TextField label="Order" type="number" value={f.sortOrder} onChange={set('sortOrder')} disabled={verified} />
        <SelectField className="span-2" label="Parent provision" value={f.parentKey} onChange={set('parentKey')} disabled={verified} placeholder="— None —"
          options={sections.filter((s) => s.sectionKey !== existing?.sectionKey).map((s) => ({ value: s.sectionKey, label: `${s.level.toLowerCase()} ${s.number} ${s.heading ?? ''}` }))} />
        <TextField label="In force from" type="date" value={f.effectiveFrom} onChange={set('effectiveFrom')} disabled={verified} />
        <TextField className="span-all" label="Heading" value={f.heading} onChange={set('heading')} disabled={verified} />
        <TextArea className="span-all" label="Text" rows={7} value={f.content} onChange={set('content')} help="Enter the exact text from the official Gazette." />
        <div className="field span-all">
          <label>Topics</label>
          <div className="row">{topics.map((t) => (
            <label key={t.code} className="checkbox"><input type="checkbox" checked={tags.includes(t.code)} onChange={() => setTags(toggle(tags, t.code))} /> {t.nameEn}</label>
          ))}</div>
        </div>
        <div className="field span-all">
          <label>Applies to designations</label>
          <div className="row">{designations.map((x) => (
            <label key={x.id} className="checkbox"><input type="checkbox" checked={des.includes(x.id)} onChange={() => setDes(toggle(des, x.id))} /> {x.name}</label>
          ))}</div>
        </div>
      </div>
    </Modal>
  );
}

function AmendModal({ doc, section, sections, onClose, onSaved }: { doc: LegalDocument; section: LegalSection | null; sections: LegalSection[]; onClose: () => void; onSaved: () => void }) {
  const toast = useToast();
  const docs = useApi<LegalDocument[]>('/legal/documents');
  const [f, setF] = useState({
    amendmentType: section ? 'SUBSTITUTED' : 'INSERTED', amendingDocumentId: '', description: '', effectiveDate: new Date().toISOString().slice(0, 10),
    number: '', heading: section?.heading ?? '', content: section?.content ?? '', parentKey: '',
  });
  const set = (k: keyof typeof f) => (v: string) => setF({ ...f, [k]: v });
  const save = async () => {
    try {
      await api.post(`/legal/documents/${doc.id}/amendments`, {
        ...f, sectionId: section?.id ?? null, amendingDocumentId: f.amendingDocumentId ? Number(f.amendingDocumentId) : null, parentKey: f.parentKey || null,
        heading: f.amendmentType === 'REPEALED' ? null : f.heading, content: f.amendmentType === 'REPEALED' ? null : f.content,
      });
      toast('Amendment recorded; previous text preserved', 'success');
      onSaved();
      onClose();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };
  return (
    <Modal title={section ? `Amend ${section.level.toLowerCase()} ${section.number}` : 'Record inserted provision'} onClose={onClose} wide footer={
      <>
        <button className="btn btn-outline" onClick={onClose}>Cancel</button>
        <button className="btn btn-gold" disabled={!f.description} onClick={() => void save()}>Record amendment</button>
      </>
    }>
      <div className="form-grid">
        <SelectField label="Amendment type" required value={f.amendmentType} onChange={set('amendmentType')} placeholder={null}
          options={(section ? ['SUBSTITUTED', 'MODIFIED', 'REPEALED'] : ['INSERTED']).map((x) => ({ value: x, label: x.toLowerCase() }))} />
        <TextField label="Effective date" type="date" required value={f.effectiveDate} onChange={set('effectiveDate')} />
        <SelectField className="span-2" label="Amending instrument" value={f.amendingDocumentId} onChange={set('amendingDocumentId')} placeholder="— Select document —"
          options={(docs.data ?? []).filter((x) => x.id !== doc.id).map((x) => ({ value: String(x.id), label: `${x.docNumber} — ${x.title}` }))} />
        <TextArea className="span-2" label="Description" required value={f.description} onChange={set('description')} />
        {!section && (
          <>
            <TextField label="New provision number" required value={f.number} onChange={set('number')} />
            <SelectField label="Under" value={f.parentKey} onChange={set('parentKey')} placeholder="— Top level —"
              options={sections.map((s) => ({ value: s.sectionKey, label: `${s.level.toLowerCase()} ${s.number}` }))} />
          </>
        )}
        {f.amendmentType !== 'REPEALED' && (
          <>
            <TextField className="span-2" label="Heading" value={f.heading} onChange={set('heading')} />
            <TextArea className="span-2" label="New text" rows={6} value={f.content} onChange={set('content')} />
          </>
        )}
      </div>
    </Modal>
  );
}

function SectionVersions({ section, onClose }: { section: LegalSection; onClose: () => void }) {
  const { data, loading } = useApi<LegalSection[]>(`/legal/sections/${section.id}/versions`);
  return (
    <Modal title={`Version history — ${section.level.toLowerCase()} ${section.number}`} onClose={onClose} wide>
      {loading && !data ? <Loading /> : (
        <ul className="timeline">
          {(data ?? []).map((v) => (
            <li key={v.id}>
              <div className="row between"><strong>Version {v.versionNo} {v.heading ? `— ${v.heading}` : ''}</strong>
                <span className="when">{fmtDate(v.effectiveFrom)} → {v.effectiveTo ? fmtDate(v.effectiveTo) : 'in force'}</span></div>
              <div style={{ whiteSpace: 'pre-wrap', marginTop: 4 }} className="muted">{v.content || '—'}</div>
            </li>
          ))}
        </ul>
      )}
    </Modal>
  );
}
