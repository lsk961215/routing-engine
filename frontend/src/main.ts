import "./style.css";
import { setupCoverage } from "./coverage";
import { setupExcludedRoads } from "./excluded-roads";
import "maplibre-gl/dist/maplibre-gl.css";

import { Map, Marker, NavigationControl, ScaleControl } from "maplibre-gl";

let startPoint: [number, number] | null = null;
let endPoint: [number, number] | null = null;

let startMarker: Marker | null = null;
let endMarker: Marker | null = null;

let pendingRoute: AbortController | null = null;

interface OsrmGeometry {
  type: "LineString";
  coordinates: [number, number][];
}

interface OsrmRoute {
  geometry: OsrmGeometry;
  distance: number;
  duration: number | null;
}

interface OsrmWaypoint {
  name: string;
  location: [number, number];
  distance: number;
}

interface OsrmResponse {
  code: string;
  routes: OsrmRoute[];
  waypoints: OsrmWaypoint[];
  metrics: { algorithm: string; snapMillis: number; searchMillis: number; expandedStates: number };
}

const mapElement = document.querySelector<HTMLDivElement>("#app");

if (!mapElement) {
  throw new Error("지도 컨테이너를 찾을 수 없습니다.");
}

mapElement.innerHTML = `
  <aside id="routing-sidebar" class="sidebar" aria-label="경로 설정">
    <header><span class="eyebrow">ROUTING ENGINE</span><h1>경로 탐색</h1></header>
    <section class="point-controls" aria-label="출발지와 도착지">
      <button id="select-start" class="point-select" disabled aria-pressed="true"><span class="point-badge start">A</span><span><strong>출발지</strong><span id="start-road">지도에서 선택</span></span></button>
      <button id="select-end" class="point-select" disabled aria-pressed="false"><span class="point-badge end">B</span><span><strong>도착지</strong><span id="end-road">지도에서 선택</span></span></button>
      <div class="point-actions"><button id="swap-points" disabled>출발·도착 바꾸기</button><button id="reset-points" disabled>초기화</button></div>
    </section>
    <fieldset id="algorithm" class="algorithm-options">
      <legend>탐색 알고리즘</legend>
      <div class="algorithm-buttons">
        <label><input type="radio" name="algorithm" value="dijkstra" checked><span><strong>Dijkstra</strong><small>기준 탐색</small></span></label>
        <label><input type="radio" name="algorithm" value="astar"><span><strong>A*</strong><small>목적지 방향 탐색</small></span></label>
      </div>
    </fieldset>
    <button id="search-route" class="primary" disabled>경로 탐색</button>
    <p id="routing-status" class="status" role="status" aria-live="polite">지도를 불러오는 중입니다.</p>
    <section id="route-info" class="route-info hidden" aria-label="탐색 결과"><div><span>총 거리</span><strong id="route-distance">-</strong></div><div><span>예상 시간</span><strong id="route-duration">제공 예정</strong></div><div><span>알고리즘</span><strong id="result-algorithm">-</strong></div><div><span>탐색·경로 복원</span><strong id="search-time">-</strong></div><div><span>확장 상태 수</span><strong id="expanded-states">-</strong></div><small id="snap-time"></small></section>
    <section class="exclusion-toolbar" aria-labelledby="map-options-title">
      <h2 id="map-options-title">지도 표시</h2>
      <div class="map-options">
        <div class="map-option-row">
          <label><input id="show-coverage" type="checkbox" checked disabled><span class="layer-swatch coverage-swatch" aria-hidden="true"></span>데이터 영역</label>
          <button id="coverage-overview" aria-label="데이터 영역 전체 보기" disabled>전체 보기</button>
        </div>
        <div class="map-option-row">
          <label><input id="show-exclusions" type="checkbox" disabled><span class="layer-swatch excluded-swatch" aria-hidden="true"></span>제외 도로</label>
        </div>
      </div>
      <span id="exclusion-status" role="status" aria-live="polite"></span>
    </section>
  </aside>
  <button id="sidebar-toggle" class="sidebar-toggle" type="button" aria-controls="routing-sidebar" aria-expanded="true" aria-label="사이드 패널 접기" title="사이드 패널 접기"><svg viewBox="0 0 20 20" aria-hidden="true" focusable="false"><path d="m12.5 5-5 5 5 5" /></svg></button>
  <main id="map" class="map" aria-label="경로 지도"></main>
`;

let selection: "start" | "end" | null = "start";
let ready = false;
const button = (id: string) => document.querySelector<HTMLButtonElement>(`#${id}`)!;
const status = () => document.querySelector<HTMLElement>("#routing-status")!;
function updatePanel() {
  for (const [kind, point] of [["start", startPoint], ["end", endPoint]] as const) {
    document.querySelector<HTMLElement>(`#${kind}-road`)!.textContent = point ? `${point[1].toFixed(6)}, ${point[0].toFixed(6)}` : "지도에서 선택";
    button(`select-${kind}`).setAttribute("aria-pressed", String(selection === kind));
    button(`select-${kind}`).disabled = !ready;
  }
  button("search-route").disabled = !ready || !startPoint || !endPoint || !!pendingRoute;
  button("swap-points").disabled = !ready || !startPoint || !endPoint;
  button("reset-points").disabled = !ready || (!startPoint && !endPoint);
  map.getCanvas().style.cursor = selection ? "crosshair" : "";
}
function updateMarkers() {
  startMarker?.remove(); endMarker?.remove();
  startMarker = startPoint ? new Marker({ color: "#16a34a" }).setLngLat(startPoint).addTo(map) : null;
  endMarker = endPoint ? new Marker({ color: "#ef4444" }).setLngLat(endPoint).addTo(map) : null;
}
function invalidateRoute() {
  pendingRoute?.abort(); pendingRoute = null;
  resetRoute();
  document.querySelector("#route-info")!.classList.add("hidden");
}

const map = new Map({
  container: "map",

  // 개발 확인용 MapLibre 데모 벡터 스타일
  style: "https://tiles.openfreemap.org/styles/bright",

  // 경도, 위도 순서
  center: [126.978, 37.5665],

  zoom: 11,

  // 한글·중국어·일본어 문자를 로컬 글꼴로 표현
  localIdeographFontFamily:
    '"Noto Sans CJK KR", "Apple SD Gothic Neo", sans-serif',
});

const sidebar = document.querySelector<HTMLElement>("#routing-sidebar")!;
const sidebarToggle = document.querySelector<HTMLButtonElement>("#sidebar-toggle")!;
new ResizeObserver(() => {
  mapElement.style.setProperty("--sidebar-width", `${sidebar.offsetWidth}px`);
  mapElement.style.setProperty("--sidebar-height", `${sidebar.offsetHeight}px`);
  map.resize();
}).observe(sidebar);
sidebarToggle.addEventListener("click", () => {
  const collapsed = !sidebar.hidden;
  sidebar.hidden = collapsed;
  mapElement.classList.toggle("sidebar-collapsed", collapsed);
  sidebarToggle.setAttribute("aria-expanded", String(!collapsed));
  const label = collapsed ? "사이드 패널 펼치기" : "사이드 패널 접기";
  sidebarToggle.setAttribute("aria-label", label);
  sidebarToggle.title = label;
  requestAnimationFrame(() => map.resize());
});

const inspectExcludedRoad = setupExcludedRoads(map);
setupCoverage(map);

map.addControl(
  new NavigationControl({
    showCompass: true,
    showZoom: true,
  }),
  "top-right",
);

map.addControl(
  new ScaleControl({
    unit: "metric",
  }),
  "bottom-left",
);

map.on("load", () => {
  ready = true;
  updatePanel();
  document.querySelector<HTMLElement>("#routing-status")!.textContent =
    "";
});

map.on("error", (event) => {
  console.error("지도 로딩 오류:", event.error);
  if (!map.isStyleLoaded()) document.querySelector<HTMLElement>("#routing-status")!.textContent =
    "배경 지도를 불러오지 못했습니다. 인터넷 연결을 확인하고 새로고침해 주세요.";
});

for (const kind of ["start", "end"] as const) {
  button(`select-${kind}`).addEventListener("click", () => {
    selection = kind;
    updatePanel();
    status().textContent = "";
  });
}
document.querySelector<HTMLFieldSetElement>("#algorithm")!.addEventListener("change", () => {
  invalidateRoute(); updatePanel();
  status().textContent = "알고리즘을 변경했습니다. 경로 탐색을 눌러주세요.";
});
button("reset-points").addEventListener("click", resetPoints);
button("search-route").addEventListener("click", () => { void findRoute(); });
button("swap-points").addEventListener("click", () => {
  invalidateRoute();
  [startPoint, endPoint] = [endPoint, startPoint];
  selection = null;
  updateMarkers(); updatePanel();
  status().textContent = "출발지와 도착지를 바꿨습니다. 경로 탐색을 눌러주세요.";
});
map.on("click", (event) => {
  if (!ready || inspectExcludedRoad(event) || !selection) return;
  invalidateRoute();
  const point: [number, number] = [event.lngLat.lng, event.lngLat.lat];
  if (selection === "start") startPoint = point;
  else endPoint = point;
  selection = !startPoint ? "start" : !endPoint ? "end" : null;
  updateMarkers(); updatePanel();
  status().textContent = selection ? "" : "두 지점이 준비됐습니다. 경로 탐색을 눌러주세요.";
});

async function findRoute() {
  if (!startPoint || !endPoint) {
    console.error("출발점과 도착점이 필요합니다.");
    return;
  }

  invalidateRoute();
  selection = null;
  const request = new AbortController();
  pendingRoute = request;
  updatePanel();
  const status = document.querySelector<HTMLElement>("#routing-status")!;
  status.textContent = "경로를 찾고 있습니다…";
  const params = new URLSearchParams({
    startLon: String(startPoint[0]), startLat: String(startPoint[1]),
    endLon: String(endPoint[0]), endLat: String(endPoint[1]),
    algorithm: document.querySelector<HTMLInputElement>('input[name="algorithm"]:checked')!.value,
  });
  try {
    const response = await fetch(`/api/route?${params}`, { signal: request.signal });
    if (!response.ok) {
      const messages: Record<number, string> = {
        400: "좌표를 확인해 주세요.", 404: "두 지점을 연결하는 경로가 없습니다.",
        422: "지원 영역의 도로 가까이를 선택해 주세요.", 503: "경로 서비스가 준비되지 않았습니다.",
      };
      throw new Error(messages[response.status] ?? "경로 요청에 실패했습니다.");
    }
    const data: OsrmResponse = await response.json();
    if (pendingRoute !== request) return;
    drawRoute(data.routes[0].geometry);
    showRouteInfo(data);
    // Keep selected input coordinates stable; the route geometry shows snapped endpoints.
    status.textContent = "선택 지점 근처 도로 사이의 경로입니다. 지점을 바꾸려면 패널에서 출발지 또는 도착지를 눌러주세요.";
  } catch (error) {
    if (request.signal.aborted || pendingRoute !== request) return;
    status.textContent = error instanceof Error ? error.message : "경로 요청에 실패했습니다.";
  } finally {
    if (pendingRoute === request) { pendingRoute = null; updatePanel(); }
  }
}

function drawRoute(geometry: OsrmGeometry) {
  map.addSource("route", {
    type: "geojson",
    data: {
      type: "Feature",
      properties: {},
      geometry,
    },
  });

  map.addLayer({
    id: "route",
    type: "line",
    source: "route",
    layout: {
      "line-join": "round",
      "line-cap": "round",
    },
    paint: {
      "line-color": "#2563eb",
      "line-width": 6,
    },
  });
}

function resetPoints() {
  invalidateRoute();
  startPoint = null; endPoint = null; selection = "start";
  updateMarkers(); updatePanel();
  status().textContent = "";
}

function resetRoute() {
  if (map.getLayer("route")) {
    map.removeLayer("route");
  }

  if (map.getSource("route")) {
    map.removeSource("route");
  }
}

function showRouteInfo(data: OsrmResponse) {
  const route = data.routes[0];


  const distanceText = route.distance < 1000 ? `${Math.round(route.distance)} m` : `${(route.distance / 1000).toFixed(1)} km`;

  const durationText = "제공 예정";

  const routeInfo = document.querySelector<HTMLDivElement>("#route-info");
  const routeDistance = document.querySelector<HTMLElement>("#route-distance");
  const routeDuration = document.querySelector<HTMLElement>("#route-duration");

  if (
    !routeInfo ||
    !routeDistance ||
    !routeDuration
  ) {
    return;
  }



  routeDistance.textContent = distanceText;
  routeDuration.textContent = durationText;

  document.querySelector("#result-algorithm")!.textContent = data.metrics.algorithm === "astar" ? "A*" : "Dijkstra";
  document.querySelector("#search-time")!.textContent = `${data.metrics.searchMillis.toFixed(2)} ms`;
  document.querySelector("#expanded-states")!.textContent = data.metrics.expandedStates.toLocaleString();
  document.querySelector("#snap-time")!.textContent = `좌표 연결 ${data.metrics.snapMillis.toFixed(2)} ms · 단일 요청 측정`;
  routeInfo.classList.remove("hidden");
}
