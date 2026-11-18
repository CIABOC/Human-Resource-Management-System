import { ReactNode, useEffect, useId, useRef } from 'react';
import { AlertCircle, AlertTriangle, CheckCircle2, Info, Inbox, X } from 'lucide-react';
import { useTranslation } from 'react-i18next';

/* ------------------------------------------------------------------ layout */

export function PageHeader({ eyebrow, title, subtitle, actions }: { eyebrow?: string; title: string; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="page-header">
      <div>
        {eyebrow && <div className="eyebrow">{eyebrow}</div>}
        <h1>{title}</h1>
        {subtitle && <div className="sub">{subtitle}</div>}
        <div className="gold-rule" />
      </div>
      {actions && <div className="actions">{actions}</div>}
    </div>
  );
}

export function Card({ title, actions, children, accent, pad = true, className = '' }: {
  title?: ReactNode; actions?: ReactNode; children: ReactNode; accent?: boolean; pad?: boolean; className?: string;
}) {
  return (
    <section className={`card ${accent ? 'accent' : ''} ${className}`}>
      {(title || actions) && (
        <div className="card-header">
          {typeof title === 'string' ? <h3>{title}</h3> : title}
          {actions && <div className="row">{actions}</div>}
        </div>
      )}
      <div className={pad ? 'card-body' : ''}>{children}</div>
    </section>
  );
}

export function Loading() {
  const { t } = useTranslation();
  return (
    <div className="loading-block" aria-label={t('common.loading', 'Loading...')}>
      <div className="spinner" />
    </div>
  );
}

export function Empty({ text, children }: { text?: string; children?: ReactNode }) {
  const { t } = useTranslation();
  return (
    <div className="empty">
      <Inbox />
      <div>{text ?? t('common.noRecords', 'No records found')}</div>
      {children}
    </div>
  );
}

export function Alert({ kind = 'info', children }: { kind?: 'info' | 'warning' | 'danger' | 'success'; children: ReactNode }) {
  const Icon = kind === 'danger' ? AlertCircle : kind === 'warning' ? AlertTriangle : kind === 'success' ? CheckCircle2 : Info;
  return (
    <div className={`alert alert-${kind}`} role={kind === 'danger' ? 'alert' : 'status'}>
      <Icon />
      <div>{children}</div>
    </div>
  );
}

/* ------------------------------------------------------------------ badges */

const STATUS_TONES: Record<string, string> = {
  ACTIVE: 'success', APPROVED: 'success', APPLIED: 'success', COMPLETED: 'success', FINALIZED: 'success', PAID: 'success',
  PRESENT: 'success', VERIFIED: 'success', SENT: 'success', CLEAN: 'success', ACKNOWLEDGED: 'success', SELECTED: 'success',
  PENDING: 'gold', SUBMITTED: 'gold', IN_REVIEW: 'gold', CALCULATED: 'gold', DRAFT: 'neutral', OPEN: 'info', SCHEDULED: 'info',
  RETURNED: 'warning', ON_LEAVE: 'info', PROBATION: 'info', SECONDED: 'info', LATE: 'warning', SIMULATED: 'info', ESCALATED: 'warning',
  REJECTED: 'danger', CANCELLED: 'neutral', ABSENT: 'danger', FAILED: 'danger', TERMINATED: 'danger', EXPIRED: 'danger',
  RETIRED: 'neutral', RESIGNED: 'neutral', TRANSFERRED: 'neutral', SUPERSEDED: 'neutral', ARCHIVED: 'neutral', CLOSED: 'neutral', INACTIVE: 'neutral',
};

export function StatusBadge({ status, label }: { status?: string | null; label?: string }) {
  if (!status) return null;
  const tone = STATUS_TONES[status] ?? 'neutral';
  return <span className={`badge badge-${tone}`}>{label ?? status.replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase())}</span>;
}

export function Badge({ tone = 'neutral', children }: { tone?: 'neutral' | 'gold' | 'success' | 'danger' | 'warning' | 'info'; children: ReactNode }) {
  return <span className={`badge badge-${tone}`}>{children}</span>;
}

/* ------------------------------------------------------------------ tabs */

export function Tabs<T extends string>({ tabs, value, onChange }: { tabs: { key: T; label: string }[]; value: T; onChange: (k: T) => void }) {
  return (
    <div className="tabs" role="tablist">
      {tabs.map((t) => (
        <button key={t.key} role="tab" aria-selected={value === t.key} className={`tab ${value === t.key ? 'active' : ''}`} onClick={() => onChange(t.key)}>
          {t.label}
        </button>
      ))}
    </div>
  );
}

/* ------------------------------------------------------------------ modal */

export function Modal({ title, onClose, children, footer, wide }: { title: string; onClose: () => void; children: ReactNode; footer?: ReactNode; wide?: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    ref.current?.querySelector<HTMLElement>('input, select, textarea, button')?.focus();
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className={`modal ${wide ? 'wide' : ''}`} role="dialog" aria-modal="true" aria-label={title} ref={ref}>
        <div className="modal-header">
          <h2>{title}</h2>
          <button className="icon-btn" onClick={onClose} aria-label="Close">
            <X size={18} />
          </button>
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-footer">{footer}</div>}
      </div>
    </div>
  );
}

export function ConfirmDialog({ title, message, confirmLabel = 'Confirm', danger, onConfirm, onCancel, busy, children }: {
  title: string; message: ReactNode; confirmLabel?: string; danger?: boolean; onConfirm: () => void; onCancel: () => void; busy?: boolean; children?: ReactNode;
}) {
  return (
    <Modal title={title} onClose={onCancel} footer={
      <>
        <button className="btn btn-outline" onClick={onCancel}>Cancel</button>
        <button className={`btn ${danger ? 'btn-danger' : 'btn-primary'}`} onClick={onConfirm} disabled={busy}>{confirmLabel}</button>
      </>
    }>
      <div className="stack">
        <div>{message}</div>
        {children}
      </div>
    </Modal>
  );
}

/* ------------------------------------------------------------------ form fields */

export function Field({ label, required, error, help, children, className = '' }: {
  label: string; required?: boolean; error?: string; help?: string; children: (id: string) => ReactNode; className?: string;
}) {
  const id = useId();
  return (
    <div className={`field ${className}`}>
      <label htmlFor={id}>
        {label}
        {required && <span className="req" aria-hidden>*</span>}
      </label>
      {children(id)}
      {help && !error && <span className="help">{help}</span>}
      {error && <span className="err" role="alert">{error}</span>}
    </div>
  );
}

type InputProps = Omit<React.InputHTMLAttributes<HTMLInputElement>, 'onChange' | 'value'> & {
  label: string; value: string | number | null | undefined; onChange: (v: string) => void; error?: string; help?: string; className?: string;
};

export function TextField({ label, value, onChange, error, help, required, className, ...rest }: InputProps) {
  return (
    <Field label={label} required={required} error={error} help={help} className={className}>
      {(id) => (
        <input id={id} className={`input ${error ? 'invalid' : ''}`} value={value ?? ''} onChange={(e) => onChange(e.target.value)} required={required} aria-invalid={!!error} {...rest} />
      )}
    </Field>
  );
}

export function TextArea({ label, value, onChange, error, required, className, rows = 3, help }: {
  label: string; value: string | null | undefined; onChange: (v: string) => void; error?: string; required?: boolean; className?: string; rows?: number; help?: string;
}) {
  return (
    <Field label={label} required={required} error={error} className={className} help={help}>
      {(id) => <textarea id={id} rows={rows} className={`textarea ${error ? 'invalid' : ''}`} value={value ?? ''} onChange={(e) => onChange(e.target.value)} />}
    </Field>
  );
}

export interface Option {
  value: string;
  label: string;
}

export function SelectField({ label, value, onChange, options, error, required, placeholder = '— Select —', className, disabled, help }: {
  label: string; value: string | number | null | undefined; onChange: (v: string) => void; options: Option[]; error?: string;
  required?: boolean; placeholder?: string | null; className?: string; disabled?: boolean; help?: string;
}) {
  return (
    <Field label={label} required={required} error={error} className={className} help={help}>
      {(id) => (
        <select id={id} className={`select ${error ? 'invalid' : ''}`} value={value ?? ''} onChange={(e) => onChange(e.target.value)} disabled={disabled}>
          {placeholder !== null && <option value="">{placeholder}</option>}
          {options.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
      )}
    </Field>
  );
}

export function Checkbox({ label, checked, onChange }: { label: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <label className="checkbox">
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      {label}
    </label>
  );
}

/* ------------------------------------------------------------------ display helpers */

export function DL({ items }: { items: [string, ReactNode][] }) {
  return (
    <dl className="dl">
      {items.map(([k, v]) => (
        <div key={k} style={{ display: 'contents' }}>
          <dt>{k}</dt>
          <dd>{v === null || v === undefined || v === '' ? <span className="faint">—</span> : v}</dd>
        </div>
      ))}
    </dl>
  );
}

export function KpiCard({ label, value, hint, tone = 'navy' }: { label: string; value: ReactNode; hint?: string; tone?: string }) {
  return (
    <div className={`card kpi ${tone}`}>
      <div className="label">{label}</div>
      <div className="value">{value}</div>
      {hint && <div className="hint">{hint}</div>}
    </div>
  );
}
