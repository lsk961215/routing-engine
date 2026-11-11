import { Map, Marker, NavigationControl, LngLatBounds, setWorkerUrl } from "maplibre-gl";
import { algorithms, algorithmInfo } from "./demo-algorithms";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";
import { setupExploration, type Algorithm, type PlaybackState } from "./demo-exploration";

export interface Distribution { count: number; median: number; p95: number }
export interface DemoRoute {
  algorithm: Algorithm;
  distance: number;
  geometry: { type: "LineString"; coordinates: [number, number][] };
  searchMillis: Distribution;
  expandedStates: number;
  inspectedEdges: number;
  maxQueueSize: number;
  baselineSearchMillis: Distribution;
  allocatedBytes: Distribution | null;
}
export interface Example { id: string; name: string; start: [number, number]; end: [number, number]; routes: DemoRoute[] }

/** MapLibre owns the map DOM; React owns controls and receives state through callbacks. */
export function createDemoMap(container: HTMLElement, initial: Example, graphSha256: string, callbacks: {
  onReady: () => void;
  onError: (message: string) => void;
  onPlayback: (state: PlaybackState) => void;
}) {
  let selected = initial;
  let ready = false;
  let markers: Marker[] = [];
  setWorkerUrl(workerUrl);
  const map = new Map({ container, style: "https://tiles.openfreemap.org/styles/positron", center: [126.978, 37.5665], zoom: 11, localIdeographFontFamily: '"Apple SD Gothic Neo", "Noto Sans CJK KR", sans-serif' });
  map.addControl(new NavigationControl({ showCompass: false }), "top-right");
  const exploration = setupExploration(map, graphSha256, fit, callbacks.onPlayback);
  function fit() {
    if (!ready) return;
    const bounds = new LngLatBounds();
    for (const route of selected.routes) for (const coordinate of route.geometry.coordinates) bounds.extend(coordinate);
    for (const coordinate of exploration.bounds()) bounds.extend(coordinate);
    bounds.extend(selected.start).extend(selected.end);
    map.fitBounds(bounds, { padding: container.clientWidth < 500 ? 40 : 60, maxZoom: 14, duration: 0 });
  }
  function draw() {
    if (!ready) return;
    for (const algorithm of algorithms) {
      if (map.getLayer(algorithm)) map.removeLayer(algorithm);
      if (map.getSource(algorithm)) map.removeSource(algorithm);
      const route = selected.routes.find(r => r.algorithm === algorithm)!;
      map.addSource(algorithm, { type: "geojson", data: { type: "Feature", properties: {}, geometry: route.geometry } });
      map.addLayer({ id: algorithm, type: "line", source: algorithm, layout: { "line-join": "round", "line-cap": "round", visibility: "none" }, paint: {
        "line-color": algorithmInfo[algorithm].color, "line-width": algorithmInfo[algorithm].width,
        ...(algorithm === "astar" ? { "line-dasharray": [2, 2] } : {}),
      } });
    }
    markers.forEach(marker => marker.remove());
    markers = ([selected.start, selected.end] as const).map((coordinate, index) => {
      const element = document.createElement("div");
      element.className = `endpoint endpoint-${index}`;
      element.textContent = index === 0 ? "A" : "B";
      element.setAttribute("role", "img");
      element.setAttribute("aria-label", index === 0 ? "출발지" : "도착지");
      return new Marker({ element }).setLngLat(coordinate).addTo(map);
    });
    void exploration.load(selected);
    fit();
  }
  map.on("load", () => { ready = true; callbacks.onReady(); draw(); });
  map.on("error", () => callbacks.onError("배경 지도를 불러오지 못했습니다. 인터넷 연결을 확인해 주세요. 저장된 측정 결과는 계속 볼 수 있습니다."));
  const resize = new ResizeObserver(() => { map.resize(); fit(); });
  resize.observe(container);
  return {
    exploration, fit,
    select: (example: Example) => { selected = example; draw(); },
    destroy: () => { resize.disconnect(); exploration.destroy(); markers.forEach(marker => marker.remove()); map.remove(); },
  };
}
