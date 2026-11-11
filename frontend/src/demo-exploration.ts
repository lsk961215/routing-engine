import type { Map as MapLibreMap } from "maplibre-gl";

import { algorithms, algorithmInfo, type Algorithm } from "./demo-algorithms";
export type { Algorithm } from "./demo-algorithms";
interface Trace {
  algorithm: Algorithm;
  expandedStates: number;
  points: [number, number, number][];
  bounds: [number, number, number, number];
}
interface TraceData {
  version: number;
  exampleId: string;
  graphSha256: string;
  cellMetres: number;
  traces: Trace[];
}
interface Example {
  id: string;
  routes: { algorithm: Algorithm; expandedStates: number }[];
}
export interface PlaybackState {
  status: "loading" | "ready" | "error";
  playing: boolean;
  progress: number;
  step: number;
  counts: Partial<Record<Algorithm, { current: number; total: number }>>;
  message: string;
}
export const initialPlayback: PlaybackState = { status: "loading", playing: false, progress: 0, step: 0, counts: {}, message: "지도 준비 중" };
export type Visibility = Record<Algorithm, boolean> & { exploration: boolean };


function validate(data: TraceData, example: Example, graphSha256: string) {
  if (data.version !== 3 || data.exampleId !== example.id || data.graphSha256 !== graphSha256 || data.traces.length !== algorithms.length || data.cellMetres !== 120)
    throw new Error("예제와 탐색 기록이 일치하지 않습니다.");
  for (const algorithm of algorithms) {
    const trace = data.traces.find(item => item.algorithm === algorithm);
    const expected = example.routes.find(item => item.algorithm === algorithm)!;
    if (!trace || trace.expandedStates !== expected.expandedStates || trace.points.length === 0 || trace.bounds.length !== 4 || !trace.bounds.every(Number.isFinite))
      throw new Error("탐색 기록이 완전하지 않습니다.");
    let previous = 0;
    for (const [lon, lat, step] of trace.points) {
      if (!Number.isFinite(lon) || !Number.isFinite(lat) || Math.abs(lon) > 180 || Math.abs(lat) > 90 || !Number.isSafeInteger(step) || step <= previous || step > trace.expandedStates)
        throw new Error("탐색 순서 또는 좌표가 올바르지 않습니다.");
      previous = step;
    }
  }
}

/** Owns trace requests and MapLibre layers; React receives playback state through onChange. */
export function setupExploration(map: MapLibreMap, graphSha256: string, fit: () => void, onChange: (state: PlaybackState) => void) {
  let data: TraceData | null = null;
  let current: Example | null = null;
  let request: AbortController | null = null;
  let frame = 0;
  let playing = false;
  let progress = 0;
  let maximum = 0;
  let disposed = false;
  let status: PlaybackState["status"] = "loading";
  let message = "";
  let visibility: Visibility = { dijkstra: true, astar: true, alt: true, bidirectional: true, exploration: true };

  // Map, counters and seek position share the actual expansion fraction.
  // Easing belongs only to the playback clock, never to the control bar.
  function step() { return Math.floor(maximum * progress); }
  function publish() {
    if (disposed) return;
    onChange({ status, playing, progress, step: step(), message,
      counts: Object.fromEntries(data?.traces.map(trace => [trace.algorithm, {
        current: Math.min(step(), trace.expandedStates), total: trace.expandedStates,
      }]) ?? []),
    });
  }
  function pause() { cancelAnimationFrame(frame); playing = false; render(); }
  function updateVisibility() {
    for (const algorithm of algorithms) {
      const layer = `exploration-${algorithm}`;
      if (map.getLayer(layer)) map.setLayoutProperty(layer, "visibility", visibility.exploration && visibility[algorithm] ? "visible" : "none");
      const total = data?.traces.find(trace => trace.algorithm === algorithm)?.expandedStates ?? 0;
      if (map.getLayer(algorithm)) map.setLayoutProperty(algorithm, "visibility", visibility[algorithm] && (status === "error" || (data && step() >= total)) ? "visible" : "none");
    }
  }
  function render() {
    if (data) {
      map.setGlobalStateProperty("exploration-step", step());
      // Highlight a short window of the recorded sequence, not an invented radial wave.
      map.setGlobalStateProperty("exploration-tail", Math.floor(maximum * Math.max(0, Math.cbrt(progress) - 0.025) ** 3));
    }
    updateVisibility();
    publish();
  }
  function clear() {
    cancelAnimationFrame(frame);
    playing = false;
    progress = 0;
    maximum = 0;
    data = null;
    for (const algorithm of algorithms) {
      const id = `exploration-${algorithm}`;
      if (map.getLayer(id)) map.removeLayer(id);
      if (map.getSource(id)) map.removeSource(id);
    }
  }
  async function load(example: Example) {
    if (disposed) return;
    current = example;
    request?.abort();
    const pending = new AbortController();
    request = pending;
    clear();
    status = "loading";
    message = "실제 탐색 순서를 불러오는 중입니다.";
    render();
    try {
      const response = await fetch(`${import.meta.env.BASE_URL}traces/${example.id}.json`, { signal: pending.signal });
      if (!response.ok) throw new Error("탐색 기록을 불러오지 못했습니다.");
      const loaded = await response.json() as TraceData;
      if (disposed || pending.signal.aborted || request !== pending) return;
      validate(loaded, example, graphSha256);
      data = loaded;
      maximum = Math.max(...data.traces.map(trace => trace.expandedStates));
      map.setGlobalStateProperty("exploration-step", 0);
      map.setGlobalStateProperty("exploration-tail", 0);
      for (const trace of data.traces) {
        const id = `exploration-${trace.algorithm}`;
        map.addSource(id, { type: "geojson", data: { type: "FeatureCollection", features: trace.points.map(([lon, lat, ordinal]) => ({ type: "Feature", properties: { ordinal }, geometry: { type: "Point", coordinates: [lon, lat] } })) } });
        map.addLayer({ id, type: "circle", source: id, paint: {
          "circle-color": algorithmInfo[trace.algorithm].pointColor,
          // All algorithms use the same footprint. At a city-wide zoom, a fixed
          // 4.5px radius covered hundreds of metres around a 120m sample cell.
          "circle-radius": ["interpolate", ["exponential", 2], ["zoom"], 8, 0.8, 10, 1.2, 12, 2.4, 14, 3.2, 16, 4],
          "circle-opacity": ["case",
            [">", ["get", "ordinal"], ["number", ["global-state", "exploration-step"], 0]], 0,
            ["all",
              [">", ["get", "ordinal"], ["number", ["global-state", "exploration-tail"], 0]],
              ["<", ["number", ["global-state", "exploration-step"], 0], trace.expandedStates],
            ], 0.95,
            0.55,
          ],
          "circle-opacity-transition": { duration: 0 },
          "circle-blur": 0,
        } }, "dijkstra");
      }
      status = "ready";
      message = "";
      fit();
      startPlayback();
    } catch (error) {
      if (disposed || pending.signal.aborted || request !== pending) return;
      clear();
      status = "error";
      message = `${error instanceof Error ? error.message : "탐색 기록을 불러오지 못했습니다."} 경로와 측정 결과는 계속 볼 수 있습니다.`;
      render();
    }
  }
  function startPlayback() {
    if (!data || disposed) return;
    cancelAnimationFrame(frame);
    if (progress >= 1) progress = 0;
    playing = true;
    // Retain the slower opening without changing what a slider position means.
    const from = Math.cbrt(progress);
    const began = performance.now();
    let lastPaint = 0;
    render();
    const tick = (now: number) => {
      if (!playing || disposed) return;
      progress = Math.min(1, from + (now - began) / 15000) ** 3;
      if (now - lastPaint >= 33 || progress >= 1) { render(); lastPaint = now; }
      if (progress >= 1) pause();
      else frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
  }
  const onVisibilityChange = () => { if (document.hidden) pause(); };
  document.addEventListener("visibilitychange", onVisibilityChange);
  return {
    load,
    toggle: () => { if (playing) pause(); else startPlayback(); },
    seek: (position: number) => {
      if (!data || !Number.isFinite(position)) return;
      cancelAnimationFrame(frame);
      playing = false;
      progress = Math.max(0, Math.min(1, position));
      render();
    },
    retry: () => { if (current) void load(current); },
    setVisibility: (value: Visibility) => { visibility = value; updateVisibility(); },
    bounds: () => data?.traces.flatMap(trace => [[trace.bounds[0], trace.bounds[1]], [trace.bounds[2], trace.bounds[3]]] as [number, number][]) ?? [],
    destroy: () => {
      disposed = true;
      request?.abort();
      cancelAnimationFrame(frame);
      document.removeEventListener("visibilitychange", onVisibilityChange);
    },
  };
}
