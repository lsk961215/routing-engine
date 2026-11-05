import type { Map as MapLibreMap } from "maplibre-gl";

type Algorithm = "dijkstra" | "astar";
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
const algorithms: Algorithm[] = ["dijkstra", "astar"];
const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
const format = (n: number) => n.toLocaleString("ko-KR");

function validate(data: TraceData, example: Example, graphSha256: string) {
  if (data.version !== 1 || data.exampleId !== example.id || data.graphSha256 !== graphSha256 || data.traces.length !== 2 || data.cellMetres !== 120)
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

/** Loads one offline trace at a time. Animation uses a shared expansion count, never wall-clock timings. */
export function setupExploration(map: MapLibreMap, graphSha256: string, fit: () => void) {
  let data: TraceData | null = null;
  let current: Example | null = null;
  let request: AbortController | null = null;
  let frame = 0;
  let playing = false;
  let progress = 1;
  let maximum = 0;
  const play = $<HTMLButtonElement>("trace-play");
  const range = $<HTMLInputElement>("trace-position");
  const start = $<HTMLButtonElement>("trace-start");
  const end = $<HTMLButtonElement>("trace-end");
  const retry = $<HTMLButtonElement>("trace-retry");
  const show = $<HTMLInputElement>("show-exploration");
  const status = $("trace-status");

  function pause() {
    cancelAnimationFrame(frame);
    playing = false;
    play.textContent = progress >= 1 ? "처음부터 재생" : "재생";
    play.setAttribute("aria-pressed", "false");
  }
  function visible(algorithm: Algorithm) { return $<HTMLInputElement>(`show-${algorithm}`).checked; }
  function updateVisibility() {
    for (const algorithm of algorithms) {
      const layer = `exploration-${algorithm}`;
      if (map.getLayer(layer)) map.setLayoutProperty(layer, "visibility", show.checked && visible(algorithm) ? "visible" : "none");
      const total = data?.traces.find(trace => trace.algorithm === algorithm)?.expandedStates ?? 0;
      if (map.getLayer(algorithm)) map.setLayoutProperty(algorithm, "visibility", visible(algorithm) && (!data || step() >= total) ? "visible" : "none");
    }
  }
  // Ease the common counter so even short A* searches remain visible during the opening seconds.
  // This is a visual playback scale, not elapsed compute time or each algorithm's own completion %.
  function step() { return Math.floor(maximum * progress ** 3); }
  function render() {
    if (!data) return;
    const ordinal = step();
    map.setGlobalStateProperty("exploration-step", ordinal);
    range.value = String(Math.round(progress * 1000));
    range.setAttribute("aria-valuetext", `누적 확장 ${format(ordinal)}회`);
    $("trace-count").textContent = `같은 확장 횟수 ${format(ordinal)}회`;
    for (const trace of data.traces) {
      const count = Math.min(ordinal, trace.expandedStates);
      $(`trace-${trace.algorithm}-count`).textContent = `${format(count)} / ${format(trace.expandedStates)}${count === trace.expandedStates ? " · 완료" : ""}`;
    }
    updateVisibility();
  }
  function clear() {
    pause();
    data = null;
    for (const algorithm of algorithms) {
      const id = `exploration-${algorithm}`;
      if (map.getLayer(id)) map.removeLayer(id);
      if (map.getSource(id)) map.removeSource(id);
      $(`trace-${algorithm}-count`).textContent = "—";
    }
    [play, range, start, end, show].forEach(control => { control.disabled = true; });
    $("trace-count").textContent = "탐색 기록을 불러오는 중";
    retry.hidden = true;
  }
  async function load(example: Example) {
    current = example;
    request?.abort();
    const pending = new AbortController();
    request = pending;
    clear();
    status.textContent = "실제 탐색 순서를 불러오는 중입니다.";
    try {
      const response = await fetch(`${import.meta.env.BASE_URL}traces/${example.id}.json`, { signal: pending.signal });
      if (!response.ok) throw new Error("탐색 기록을 불러오지 못했습니다.");
      const loaded = await response.json() as TraceData;
      if (request !== pending) return;
      validate(loaded, example, graphSha256);
      data = loaded;
      maximum = Math.max(...data.traces.map(trace => trace.expandedStates));
      progress = 1;
      map.setGlobalStateProperty("exploration-step", maximum);
      for (const trace of data.traces) {
        const id = `exploration-${trace.algorithm}`;
        map.addSource(id, { type: "geojson", data: { type: "FeatureCollection", features: trace.points.map(([lon, lat, ordinal]) => ({ type: "Feature", properties: { ordinal }, geometry: { type: "Point", coordinates: [lon, lat] } })) } });
        map.addLayer({ id, type: "circle", source: id, paint: {
          "circle-color": trace.algorithm === "dijkstra" ? "#2563eb" : "#ed772c",
          "circle-radius": trace.algorithm === "dijkstra" ? 4.5 : 3,
          "circle-opacity": ["case", ["<=", ["get", "ordinal"], ["number", ["global-state", "exploration-step"], 0]], trace.algorithm === "dijkstra" ? 0.48 : 0.85, 0],
          "circle-opacity-transition": { duration: 0 },
          "circle-blur": 0.15,
        } }, "dijkstra");
      }
      [play, range, start, end, show].forEach(control => { control.disabled = false; });
      status.textContent = "점은 약 120m 구역마다 처음 확장한 지점입니다. 색이 겹치면 하나씩 꺼서 비교해 보세요.";
      render();
      pause();
      fit();
    } catch (error) {
      if (pending.signal.aborted || request !== pending) return;
      clear();
      updateVisibility();
      status.textContent = `${error instanceof Error ? error.message : "탐색 기록을 불러오지 못했습니다."} 경로와 측정 결과는 계속 볼 수 있습니다.`;
      $("trace-count").textContent = "탐색 기록 로딩 실패";
      retry.hidden = false;
    }
  }
  play.addEventListener("click", () => {
    if (!data) return;
    if (playing) { pause(); return; }
    if (progress >= 1) progress = 0;
    playing = true;
    play.textContent = "일시정지";
    play.setAttribute("aria-pressed", "true");
    const from = progress;
    const began = performance.now();
    let lastPaint = 0;
    render();
    const tick = (now: number) => {
      if (!playing) return;
      progress = Math.min(1, from + (now - began) / 15000);
      if (now - lastPaint >= 100 || progress >= 1) { render(); lastPaint = now; }
      if (progress >= 1) pause();
      else frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
  });
  range.addEventListener("input", () => { pause(); progress = Number(range.value) / 1000; render(); pause(); });
  start.addEventListener("click", () => { pause(); progress = 0; render(); pause(); });
  end.addEventListener("click", () => { pause(); progress = 1; render(); pause(); });
  show.addEventListener("change", updateVisibility);
  retry.addEventListener("click", () => { if (current) void load(current); });
  document.addEventListener("visibilitychange", () => { if (document.hidden) pause(); });
  return {
    load,
    updateVisibility,
    bounds: () => data?.traces.flatMap(trace => [[trace.bounds[0], trace.bounds[1]], [trace.bounds[2], trace.bounds[3]]] as [number, number][]) ?? [],
  };
}
