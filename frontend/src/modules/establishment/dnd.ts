import { PointerEvent as ReactPointerEvent, useCallback, useEffect, useRef, useState } from 'react';

/** Something being dragged: a designation card, a category, a person or a designation chip. */
export interface DragItem {
  kind: 'designation' | 'category' | 'person' | 'cadre';
  id: number;
  label: string;
}

export interface DropVerdict {
  ok: boolean;
  reason?: string;
}

interface Options {
  /** Whether the current user may drag at all (e.g. EST_MANAGE). Viewers still see the chart. */
  enabled: boolean;
  /** Fast, synchronous check used while hovering so the target lights up green or red. */
  check?: (item: DragItem, zone: string) => DropVerdict;
  onDrop: (item: DragItem, zone: string) => void;
  /** Called when a drop is attempted on a zone that {@link check} rejects. */
  onReject?: (item: DragItem, zone: string, reason?: string) => void;
  /**
   * When dragging is not allowed for this user, the explanation given (via onReject) as soon as they try to drag,
   * so the chart does not just silently ignore them.
   */
  disabledReason?: string;
}

const THRESHOLD = 6;
const EDGE = 48;

/**
 * Pointer-event drag and drop. Unlike the HTML5 drag API it works with mouse, touch and pen (tablets), inside
 * CSS-scaled canvases and with nested drop zones: the innermost element carrying {@code data-drop-zone} under the
 * pointer wins. Drop zones are plain elements with {@code {...zone(key)}}; draggables spread {@code {...handle(item)}}.
 */
export function usePointerDnd({ enabled, check, onDrop, onReject, disabledReason }: Options) {
  const [dragging, setDragging] = useState<DragItem | null>(null);
  const [over, setOver] = useState<{ zone: string; verdict: DropVerdict } | null>(null);
  const opts = useRef({ check, onDrop, onReject, disabledReason });
  opts.current = { check, onDrop, onReject, disabledReason };
  const state = useRef<{
    item: DragItem; x: number; y: number; started: boolean; ghost?: HTMLDivElement; zone?: string; px?: number; py?: number; timer?: number;
    denied?: boolean;
  } | null>(null);
  const suppressClick = useRef(false);

  const cleanup = useCallback(() => {
    state.current?.ghost?.remove();
    if (state.current?.timer) window.clearInterval(state.current.timer);
    state.current = null;
    document.body.classList.remove('dnd-active');
    setDragging(null);
    setOver(null);
  }, []);

  useEffect(() => {
    /** Re-evaluates the drop zone under the pointer (after a move, or after the chart panned underneath it). */
    const track = () => {
      const s = state.current;
      if (!s || s.px === undefined || s.py === undefined) return;
      const el = document.elementFromPoint(s.px, s.py) as HTMLElement | null;
      const zone = el?.closest<HTMLElement>('[data-drop-zone]')?.dataset.dropZone;
      if (zone !== s.zone) {
        s.zone = zone;
        setOver(zone ? { zone, verdict: opts.current.check?.(s.item, zone) ?? { ok: true } } : null);
      }
    };
    const move = (e: PointerEvent) => {
      const s = state.current;
      if (!s) return;
      if (!s.started) {
        if (Math.hypot(e.clientX - s.x, e.clientY - s.y) < THRESHOLD) return;
        if (s.denied) {
          opts.current.onReject?.(s.item, '', opts.current.disabledReason);
          state.current = null;
          return;
        }
        s.started = true;
        const g = document.createElement('div');
        g.className = `dnd-ghost dnd-ghost-${s.item.kind}`;
        g.textContent = s.item.label;
        (document.fullscreenElement ?? document.body).appendChild(g);
        s.ghost = g;
        document.body.classList.add('dnd-active');
        setDragging(s.item);
        // keep panning / scrolling while the pointer rests near an edge
        s.timer = window.setInterval(() => {
          if (s.px === undefined || s.py === undefined) return;
          autoScroll(document.elementFromPoint(s.px, s.py) as HTMLElement | null, s.px, s.py);
          track();
        }, 30);
      }
      e.preventDefault();
      s.px = e.clientX;
      s.py = e.clientY;
      if (s.ghost) s.ghost.style.transform = `translate(${e.clientX + 12}px, ${e.clientY + 10}px)`;
      track();
    };
    const up = () => {
      const s = state.current;
      if (!s) return;
      if (s.started) {
        suppressClick.current = true;
        setTimeout(() => (suppressClick.current = false), 0);
        if (s.zone) {
          const verdict = opts.current.check?.(s.item, s.zone) ?? { ok: true };
          if (verdict.ok) opts.current.onDrop(s.item, s.zone);
          else opts.current.onReject?.(s.item, s.zone, verdict.reason);
        }
      }
      cleanup();
    };
    const key = (e: KeyboardEvent) => e.key === 'Escape' && state.current?.started && cleanup();
    window.addEventListener('pointermove', move, { passive: false });
    window.addEventListener('pointerup', up);
    window.addEventListener('pointercancel', cleanup);
    window.addEventListener('keydown', key);
    return () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
      window.removeEventListener('pointercancel', cleanup);
      window.removeEventListener('keydown', key);
    };
  }, [cleanup]);

  /** Props for a draggable element. Buttons and links inside it keep working. */
  const handle = (item: DragItem, disabled = false) => ({
    'data-draggable': enabled && !disabled ? 'true' : undefined,
    onPointerDown: (e: ReactPointerEvent) => {
      if (disabled || e.button !== 0) return;
      if ((e.target as HTMLElement).closest('button, a, input, select, textarea')) return;
      if (!enabled) {
        // not allowed: remember the attempt so a drag gesture gets an explanation instead of nothing
        // (inside a chart frame the gesture pans the chart instead, so no message there)
        if (opts.current.disabledReason && !(e.target as HTMLElement).closest('[data-pan-viewport]'))
          state.current = { item, x: e.clientX, y: e.clientY, started: false, denied: true };
        return;
      }
      e.stopPropagation(); // nested draggables: the innermost one wins
      state.current = { item, x: e.clientX, y: e.clientY, started: false };
    },
    onClickCapture: (e: React.MouseEvent) => {
      if (suppressClick.current) {
        e.stopPropagation();
        e.preventDefault();
      }
    },
  });

  /** Props for a drop zone, plus a class name reflecting the hover verdict. */
  const zone = (key: string) => ({
    'data-drop-zone': key,
    className: over?.zone === key && dragging ? (over.verdict.ok ? 'dnd-over' : 'dnd-deny') : '',
  });

  return { dragging, over, handle, zone };
}

/**
 * Near an edge of a pan-and-zoom chart frame, asks the frame to pan (ChartViewport listens for {@code dnd-pan});
 * otherwise scrolls the nearest scrollable ancestor (or the window) when the pointer nears its edge.
 */
let edgeSince = 0;
/** How long the pointer must rest near a chart edge before it pans, so crossing the edge on the way in does not. */
const EDGE_DWELL = 450;

function autoScroll(el: HTMLElement | null, x: number, y: number) {
  const frame = el?.closest<HTMLElement>('[data-pan-viewport]');
  if (frame) {
    const r = frame.getBoundingClientRect();
    const dx = x < r.left + EDGE ? 14 : x > r.right - EDGE ? -14 : 0;
    const dy = y < r.top + EDGE ? 14 : y > r.bottom - EDGE ? -14 : 0;
    if (!dx && !dy) { edgeSince = 0; return; }
    const now = performance.now();
    if (!edgeSince) edgeSince = now;
    if (now - edgeSince >= EDGE_DWELL) frame.dispatchEvent(new CustomEvent('dnd-pan', { detail: { dx, dy } }));
    return;
  }
  edgeSince = 0;
  let box: HTMLElement | null = el;
  while (box && box !== document.body) {
    const st = getComputedStyle(box);
    if (/(auto|scroll)/.test(st.overflow + st.overflowX + st.overflowY) && (box.scrollHeight > box.clientHeight || box.scrollWidth > box.clientWidth)) break;
    box = box.parentElement;
  }
  if (box && box !== document.body) {
    const r = box.getBoundingClientRect();
    if (x < r.left + EDGE) box.scrollLeft -= 14;
    else if (x > r.right - EDGE) box.scrollLeft += 14;
    if (y < r.top + EDGE) box.scrollTop -= 14;
    else if (y > r.bottom - EDGE) box.scrollTop += 14;
  }
  if (y < EDGE) window.scrollBy(0, -14);
  else if (y > window.innerHeight - EDGE) window.scrollBy(0, 14);
}
