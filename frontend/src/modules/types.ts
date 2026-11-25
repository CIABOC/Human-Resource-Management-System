import type { ReactNode } from 'react';
import type { LucideIcon } from 'lucide-react';

/**
 * Sidebar sections, rendered in this order (spec G-8: Overview, Establishment, Pay & Benefits Setup, Recruitment,
 * People), plus My services, Insight and Administration.
 */
export const NAV_SECTIONS = [
  'overview',
  'self',
  'establishment',
  'payBenefits',
  'recruitment',
  'people',
  'insight',
  'administration',
] as const;

/** Earlier section keys, still accepted and mapped onto the G-8 groups by {@link resolveSection}. */
export type LegacySection = 'organization' | 'time' | 'compensation' | 'talent';
export type NavSection = (typeof NAV_SECTIONS)[number] | LegacySection;

export function resolveSection(item: { section: NavSection; path: string }): (typeof NAV_SECTIONS)[number] {
  switch (item.section) {
    case 'organization':
      return 'establishment';
    case 'compensation':
      return 'payBenefits';
    case 'time':
      return 'people';
    case 'talent':
      return item.path.startsWith('/recruitment') ? 'recruitment' : 'people';
    default:
      return item.section;
  }
}

export interface NavItem {
  path: string;
  /** i18n key; falls back to `fallback` when a translation is missing. */
  label: string;
  fallback: string;
  icon: LucideIcon;
  /** Shown if the user holds ANY of these permissions (empty = any signed-in user). */
  anyOf?: string[];
  section: NavSection;
  order: number;
}

export interface RouteDef {
  /** Absolute path, e.g. "/leave/my". */
  path: string;
  element: ReactNode;
  anyOf?: string[];
}

export interface Translations {
  en?: Record<string, string>;
  si?: Record<string, string>;
  ta?: Record<string, string>;
}

/**
 * Every feature module exports one ModuleDef (src/modules/<name>/module.tsx). The registry collects them, so adding a
 * module never requires editing shared files other than registry.ts.
 */
export interface ModuleDef {
  key: string;
  nav: NavItem[];
  routes: RouteDef[];
  i18n?: Translations;
}
