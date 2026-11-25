import { ReactNode, useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Maximize2, Minimize2, Scan, ZoomIn, ZoomOut } from 'lucide-react';

const MIN = 0.15;
const MAX = 2;

/**
 * Pan-and-zoom frame for large diagrams. The whole diagram is fitted into view when it first renders (and again
 * when its size changes, until the user zooms or pans). Scroll to zoom around the pointer, drag the background to pan,
 * or expand to fill the window. Drag-and-drop works inside it: dnd.ts pans the frame when a dragged item nears an
 * edge (event {@code dnd-pan}). Optional {@code top} (e.g. a tray of items to drag in) and {@code side} (a details
 * panel) stay visible next to the diagram, including in the expanded view.
 */
export function ChartViewport({ children, top, side, fitKey, label }: {
  children: ReactNode; top?: ReactNode; side?: ReactNode; fitKey?: unknown; label: string;
}) {
  const vp = useRef<HTMLDivElement>(null);
  const content = useRef<HTMLDivElement>(null);
  const [view, setView] = useState({ s: 1, x: 0, y: 0 });
  const [expanded, setExpanded] = useState(false);
  const touched = useRef(false);
  const pan = useRef<{ x: number; y: number; vx: number; vy: number; moved: boolean } | null>(null);
  const suppressClick = useRef(false);

  const fit = useCallback(() => {
    const v = vp.current;
    const c = content.current;
    if (!v || !c) return;
    const w = c.offsetWidth;
    const h = c.offsetHeight;
    if (!w || !h) return;
    const s = Math.max(MIN, Math.min(1, (v.clientWidth - 40) / w, (v.clientHeight - 40) / h));
    setView({ s, x: (v.clientWidth - w * s) / 2, y: 20 });
  }, []);

  const refit = () => { touched.current = false; fit(); };

  // Fit on first render, when the data changes, and whenever the diagram or frame resizes until the user takes over.
  useLayoutEffect(() => { touched.current = false; fit(); }, [fitKey, fit]);
  useEffect(() => {
    const ro = new ResizeObserver(() => { if (!touched.current) fit(); });
    if (content.current) ro.observe(content.current);
    if (vp.current) ro.observe(vp.current);
    return () => ro.disconnect();
  }, [fit]);
  useLayoutEffect(() => { touched.current = false; fit(); }, [expanded, fit]);

  const zoomAt = useCallback((factor: number, cx?: number, cy?: number) => {
    touched.current = true;
    setView((v) => {
      const r = vp.current!.getBoundingClientRect();
      const px = cx ?? r.width / 2;
      const py = cy ?? r.height / 2;
      const s = Math.max(MIN, Math.min(MAX, v.s * factor));
      return { s, x: px - ((px - v.x) * s) / v.s, y: py - ((py - v.y) * s) / v.s };
    });
  }, []);

  // Wheel zooms around the pointer (non-passive so the page does not scroll underneath).
  useEffect(() => {
    const el = vp.current;
    if (!el) return;
    const wheel = (e: WheelEvent) => {
      e.preventDefault();
      const r = el.getBoundingClientRect();
      zoomAt(Math.exp(-e.deltaY * (e.ctrlKey ? 0.01 : 0.0015)), e.clientX - r.left, e.clientY - r.top);
    };
    const dndPan = (e: Event) => {
      const { dx, dy } = (e as CustomEvent<{ dx: number; dy: number }>).detail;
      touched.current = true;
      setView((v) => ({ ...v, x: v.x + dx, y: v.y + dy }));
    };
    // The frame never scrolls natively (positions come from the transform); undo any scroll that focus or
    // scrollIntoView causes, which would otherwise shift the diagram out of line with the pan state.
    const noScroll = () => { if (el.scrollTop || el.scrollLeft) { el.scrollTop = 0; el.scrollLeft = 0; } };
    el.addEventListener('wheel', wheel, { passive: false });
    el.addEventListener('dnd-pan', dndPan);
    el.addEventListener('scroll', noScroll);
    return () => { el.removeEventListener('wheel', wheel); el.removeEventListener('dnd-pan', dndPan); el.removeEventListener('scroll', noScroll); };
  }, [zoomAt]);

  useEffect(() => {
    if (!expanded) return;
    const key = (e: KeyboardEvent) => e.key === 'Escape' && !document.body.classList.contains('dnd-active') && setExpanded(false);
    window.addEventListener('keydown', key);
    document.body.style.overflow = 'hidden';
    return () => { window.removeEventListener('keydown', key); document.body.style.overflow = ''; };
  }, [expanded]);

  // Drag the background (anything that is not draggable or a control) to pan.
  const onPointerDown = (e: React.PointerEvent) => {
    if (e.button !== 0) return;
    if ((e.target as HTMLElement).closest('[data-draggable], button, a, input, select, textarea, .cv-controls')) return;
    pan.current = { x: e.clientX, y: e.clientY, vx: view.x, vy: view.y, moved: false };
  };
  useEffect(() => {
    const move = (e: PointerEvent) => {
      const p = pan.current;
      if (!p) return;
      const dx = e.clientX - p.x;
      const dy = e.clientY - p.y;
      if (!p.moved && Math.hypot(dx, dy) < 4) return;
      p.moved = true;
      touched.current = true;
      vp.current?.classList.add('panning');
      setView((v) => ({ ...v, x: p.vx + dx, y: p.vy + dy }));
    };
    const up = () => {
      if (pan.current?.moved) {
        suppressClick.current = true;
        setTimeout(() => (suppressClick.current = false), 0);
      }
      pan.current = null;
      vp.current?.classList.remove('panning');
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
    window.addEventListener('pointercancel', up);
    return () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up); window.removeEventListener('pointercancel', up); };
  }, []);

  return (
    <div className={`cv-root ${expanded ? 'expanded' : ''}`}>
      {top}
      <div className="cv-body">
        <div ref={vp} className="cv-viewport" data-pan-viewport="" role="region" aria-label={label} onPointerDown={onPointerDown}
          onClickCapture={(e) => { if (suppressClick.current) { e.stopPropagation(); e.preventDefault(); } }}>
          <div ref={content} className="cv-content" style={{ transform: `translate(${view.x}px, ${view.y}px) scale(${view.s})` }}>
            {children}
          </div>
          <div className="cv-hint" aria-hidden>Scroll to zoom · drag the background to move</div>
          <div className="cv-controls">
            <button type="button" aria-label="Zoom in" title="Zoom in" onClick={() => zoomAt(1.2)}><ZoomIn /></button>
            <span className="cv-pct" aria-live="polite">{Math.round(view.s * 100)}%</span>
            <button type="button" aria-label="Zoom out" title="Zoom out" onClick={() => zoomAt(1 / 1.2)}><ZoomOut /></button>
            <button type="button" aria-label="Fit whole diagram" title="Fit whole diagram" onClick={refit}><Scan /></button>
            <button type="button" aria-label={expanded ? 'Exit full screen' : 'Full screen'} title={expanded ? 'Exit full screen (Esc)' : 'Full screen'}
              onClick={() => setExpanded((x) => !x)}>{expanded ? <Minimize2 /> : <Maximize2 />}</button>
          </div>
        </div>
        {side && <aside className="cv-side">{side}</aside>}
      </div>
    </div>
  );
}
