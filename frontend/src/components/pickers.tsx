import { useTranslation } from 'react-i18next';
import { useApi } from '@/hooks/useApi';
import type { Designation, EmployeeBrief, MasterDataItem, OrgUnit, Page } from '@/api/types';
import { SelectField } from './ui';

/** Localized name of a master-data item. */
export function mdName(i: Pick<MasterDataItem, 'nameEn' | 'nameSi' | 'nameTa'>, lang: string) {
  if (lang === 'si' && i.nameSi) return i.nameSi;
  if (lang === 'ta' && i.nameTa) return i.nameTa;
  return i.nameEn;
}

/** Drop-down bound to a master-data category (FR-MDM-001). */
export function MasterDataSelect({ category, label, value, onChange, required, error, className, placeholder }: {
  category: string; label: string; value: string | null | undefined; onChange: (v: string) => void; required?: boolean; error?: string; className?: string; placeholder?: string;
}) {
  const { i18n } = useTranslation();
  const { data } = useApi<MasterDataItem[]>(`/master-data/${category}`);
  return (
    <SelectField label={label} value={value} onChange={onChange} required={required} error={error} className={className} placeholder={placeholder}
      options={(data ?? []).map((i) => ({ value: i.code, label: mdName(i, i18n.language) }))} />
  );
}

/** Returns a code→label lookup for a master-data category, for rendering codes in tables. */
export function useMasterDataLabels(category: string) {
  const { i18n } = useTranslation();
  const { data } = useApi<MasterDataItem[]>(`/master-data/${category}`, { activeOnly: false });
  const map = new Map((data ?? []).map((i) => [i.code, mdName(i, i18n.language)]));
  return (code?: string | null) => (code ? map.get(code) ?? code : '—');
}

export function OrgUnitSelect({ label = 'Org unit', value, onChange, required, error, className, asOf, placeholder }: {
  label?: string; value: string | null | undefined; onChange: (v: string) => void; required?: boolean; error?: string; className?: string; asOf?: string; placeholder?: string;
}) {
  const { data } = useApi<OrgUnit[]>('/establishment/org-units', { asOf });
  const units = data ?? [];
  // Indent by depth for a readable hierarchy.
  const parent = new Map(units.map((u) => [u.code, u.parentCode]));
  const depth = (c: string) => {
    let d = 0;
    let p = parent.get(c);
    while (p && d < 8) {
      d++;
      p = parent.get(p);
    }
    return d;
  };
  return (
    <SelectField label={label} value={value} onChange={onChange} required={required} error={error} className={className} placeholder={placeholder}
      options={units.map((u) => ({ value: u.code, label: `${'  '.repeat(depth(u.code))}${u.name} (${u.code})` }))} />
  );
}

export function DesignationSelect({ label = 'Designation', value, onChange, required, error, className, placeholder }: {
  label?: string; value: number | string | null | undefined; onChange: (v: string) => void; required?: boolean; error?: string; className?: string; placeholder?: string;
}) {
  const { data } = useApi<Designation[]>('/establishment/designations');
  return (
    <SelectField label={label} value={value} onChange={onChange} required={required} error={error} className={className} placeholder={placeholder}
      options={(data ?? []).map((d) => ({ value: String(d.id), label: `${d.name}${d.grade ? ` · ${d.grade}` : ''}` }))} />
  );
}

/**
 * Employee picker backed by the scoped employee search (requires EMP_VIEW). For screens used by users without
 * EMP_VIEW, modules should expose their own option endpoint.
 */
export function EmployeeSelect({ label = 'Employee', value, onChange, required, error, className, orgUnitCode }: {
  label?: string; value: number | string | null | undefined; onChange: (v: string) => void; required?: boolean; error?: string; className?: string; orgUnitCode?: string;
}) {
  const { data } = useApi<Page<{ id: number; employeeNo: string; nameWithInitials: string; designation?: string }>>('/employees', { size: 200, orgUnitCode });
  return (
    <SelectField label={label} value={value} onChange={onChange} required={required} error={error} className={className}
      options={(data?.content ?? []).map((e) => ({ value: String(e.id), label: `${e.employeeNo} — ${e.nameWithInitials}${e.designation ? ` (${e.designation})` : ''}` }))} />
  );
}

export function employeeLabel(e?: Pick<EmployeeBrief, 'employeeNo' | 'nameWithInitials'> | null) {
  return e ? `${e.employeeNo} — ${e.nameWithInitials}` : '—';
}
