/**
 * Layer catalogue and lifecycle → style mapping for the Bot Chart's ICT
 * overlay (V4 Agent 06).
 *
 * <p>Kept apart from the component so the mapping is one table rather than a
 * pile of conditionals inside a render loop: the point of the overlay is that
 * a state on the chart means exactly one thing, everywhere.
 */

import type { DetectionFamily, DetectionStateName } from '../types/chart';

export interface LayerSpec {
  key: DetectionFamily;
  label: string;
  /** Whether the layer starts visible. */
  defaultOn: boolean;
  /** Zones are drawn as boxes; points as small markers. */
  render: 'zone' | 'point';
  colour: string;
  /** Draw the family's `meta.midline` as a dashed line inside the box. */
  midline?: boolean;
}

/**
 * Defaults follow the V4 brief: FVG, order blocks, liquidity and OTE on;
 * volume imbalance, opening gaps and structure labels on; BPR off unless the
 * owner turns it on (it is derived from two other layers already drawn, so it
 * is the first thing to clutter a chart).
 */
export const LAYERS: LayerSpec[] = [
  { key: 'fvg', label: 'FVG', defaultOn: true, render: 'zone', colour: '#4dabf7' },
  { key: 'orderBlock', label: 'Order blocks', defaultOn: true, render: 'zone', colour: '#f783ac' },
  { key: 'liquidityPool', label: 'Liquidity pools', defaultOn: true, render: 'zone', colour: '#ffd43b' },
  { key: 'volumeImbalance', label: 'Volume imbalance', defaultOn: true, render: 'zone', colour: '#9775fa' },
  { key: 'openingGapDaily', label: 'Daily gap (NDOG)', defaultOn: true, render: 'zone', colour: '#63e6be', midline: true },
  { key: 'openingGapWeekly', label: 'Weekly gap (NWOG)', defaultOn: true, render: 'zone', colour: '#38d9a9', midline: true },
  { key: 'mss', label: 'MSS', defaultOn: true, render: 'point', colour: '#ff922b' },
  { key: 'bos', label: 'BOS', defaultOn: true, render: 'point', colour: '#fcc419' },
  { key: 'displacement', label: 'Displacement', defaultOn: false, render: 'point', colour: '#868e96' },
  { key: 'bpr', label: 'BPR', defaultOn: false, render: 'zone', colour: '#e599f7' },
];

export interface StateStyle {
  /** Fill opacity of the box. */
  fill: number;
  /** Border dash pattern; empty string = solid. */
  dash: string;
  /** Muted states are drawn faint and their right edge is frozen. */
  muted: boolean;
  /** Terminal states stop extending to the right edge of the chart. */
  frozen: boolean;
}

/**
 * ONE mapping from lifecycle to appearance, used by every family:
 * solid = live and untouched, dashed = interacted with, muted+frozen = done.
 */
export function styleFor(state: DetectionStateName): StateStyle {
  switch (state) {
    case 'ACTIVE':
      return { fill: 0.16, dash: '', muted: false, frozen: false };
    case 'TOUCHED':
    case 'TESTED':
    case 'PARTIAL':
      return { fill: 0.12, dash: '4 3', muted: false, frozen: false };
    case 'BREAKER':
      // Polarity flipped but still a live level — dashed, not muted.
      return { fill: 0.14, dash: '6 3', muted: false, frozen: false };
    case 'FILLED':
    case 'BROKEN':
    case 'SWEPT':
    case 'REMOVED':
      return { fill: 0.05, dash: '2 4', muted: true, frozen: true };
    case 'POINT':
    default:
      return { fill: 0.2, dash: '', muted: false, frozen: false };
  }
}

/** Session shading windows (V4 §S10) — DISPLAY ONLY, never a gate input. */
export interface SessionWindow {
  label: string;
  timeZone: string;
  startHour: number;
  endHour: number;
  /** Display sessions vs the engine's real TRADING killzones. */
  kind: 'display' | 'trading';
  colour: string;
}

/**
 * The engine's TRADING killzones come from KillzoneClock (America/New_York
 * 09:45–12:30 and 13:45–16:00) and are the ONLY session windows any gate
 * reads. The display sessions are what a loaded ICT chart shades. They are
 * drawn distinctly and labelled distinctly on purpose: confusing the two is
 * how someone ends up "explaining" a trade by a window the engine never
 * consulted.
 */
export const SESSIONS: SessionWindow[] = [
  { label: 'NY (display)', timeZone: 'America/New_York', startHour: 7, endHour: 9, kind: 'display', colour: '#4dabf7' },
  { label: 'London open', timeZone: 'Europe/London', startHour: 7, endHour: 10, kind: 'display', colour: '#9775fa' },
  { label: 'London close', timeZone: 'Europe/London', startHour: 15, endHour: 17, kind: 'display', colour: '#e599f7' },
  { label: 'Asia', timeZone: 'Asia/Tokyo', startHour: 10, endHour: 14, kind: 'display', colour: '#63e6be' },
  { label: 'TRADING killzone AM', timeZone: 'America/New_York', startHour: 9.75, endHour: 12.5, kind: 'trading', colour: '#20c997' },
  { label: 'TRADING killzone PM', timeZone: 'America/New_York', startHour: 13.75, endHour: 16, kind: 'trading', colour: '#20c997' },
];

/** Fractional hour-of-day for an instant in a given IANA zone. */
export function hourInZone(ms: number, timeZone: string): number {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).formatToParts(new Date(ms));
  const hour = Number(parts.find((p) => p.type === 'hour')?.value ?? '0');
  const minute = Number(parts.find((p) => p.type === 'minute')?.value ?? '0');
  return hour + minute / 60;
}

/** True when the instant falls inside the window, wrapping midnight if needed. */
export function inWindow(ms: number, w: SessionWindow): boolean {
  const h = hourInZone(ms, w.timeZone);
  return w.startHour <= w.endHour
    ? h >= w.startHour && h < w.endHour
    : h >= w.startHour || h < w.endHour;
}

/** Convert a hex colour plus alpha to an rgba() string. */
export function rgba(hex: string, alpha: number): string {
  const n = parseInt(hex.slice(1), 16);
  const r = (n >> 16) & 255;
  const g = (n >> 8) & 255;
  const b = n & 255;
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}
