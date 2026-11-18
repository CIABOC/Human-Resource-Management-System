import type { ReactNode } from 'react';
import type { LucideIcon } from 'lucide-react';

/** Sidebar sections, rendered in this order. */
export const NAV_SECTIONS = [
  'overview',
  'self',
  'organization',
  'people',
  'time',
  'compensation',
  'talent',
  'insight',
  'administration',
] as const;
export type NavSection = (typeof NAV_SECTIONS)[number];

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
