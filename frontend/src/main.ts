import "./style.css";
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
}

const mapElement = document.querySelector<HTMLDivElement>("#app");

if (!mapElement) {
  throw new Error("지도 컨테이너를 찾을 수 없습니다.");
}

mapElement.innerHTML = `
  <header class="header">
    <h1>New GIS</h1>

    <div id="route-info" class="route-info hidden">
      <div class="route-point">
        <span class="point-badge start">A</span>
        <div class="point-content">
          <span class="point-label">출발</span>
          <strong id="start-road">-</strong>
        </div>
      </div>

      <div class="route-point">
        <span class="point-badge end">B</span>
        <div class="point-content">
          <span class="point-label">도착</span>
          <strong id="end-road">-</strong>
        </div>
      </div>

      <div class="route-summary">
        <div>
          <span class="summary-label">거리</span>
          <strong id="route-distance">-</strong>
        </div>

        <div>
          <span class="summary-label">예상 시간</span>
          <strong id="route-duration">-</strong>
        </div>
      </div>
    </div>
  </header>

  <p id="routing-status" role="status" aria-live="polite">시험 영역에서 출발점과 도착점을 선택하세요. 도로에서 50m 이내를 지원합니다.</p>
  <main id="map" class="map"></main>
`;

const map = new Map({
  container: "map",

  // 개발 확인용 MapLibre 데모 벡터 스타일
  style: "https://tiles.openfreemap.org/styles/bright",

  // 경도, 위도 순서
  center: [126.978, 37.5665],

  zoom: 16,

  // 한글·중국어·일본어 문자를 로컬 글꼴로 표현
  localIdeographFontFamily:
    '"Noto Sans CJK KR", "Apple SD Gothic Neo", sans-serif',
});

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
  console.log("벡터 지도가 로드되었습니다.");
});

map.on("error", (event) => {
  console.error("지도 로딩 오류:", event.error);
});

map.on("click", (event) => {
  const point: [number, number] = [event.lngLat.lng, event.lngLat.lat];

  // 첫 번째 클릭 → 출발점
  if (!startPoint) {
    startPoint = point;

    startMarker = new Marker({ color: "#22c55e" }).setLngLat(point).addTo(map);

    console.log("출발점:", startPoint);
    return;
  }

  // 두 번째 클릭 → 도착점
  if (!endPoint) {
    endPoint = point;

    endMarker = new Marker({ color: "#ef4444" }).setLngLat(point).addTo(map);

    console.log("도착점:", endPoint);

    // 출발/도착이 모두 정해졌으므로 경로탐색
    findRoute();

    return;
  }

  if (startPoint && endPoint) {
    resetPoints();
    resetRoute();
  }
});

async function findRoute() {
  if (!startPoint || !endPoint) {
    console.error("출발점과 도착점이 필요합니다.");
    return;
  }

  pendingRoute?.abort();
  const request = new AbortController();
  pendingRoute = request;
  const status = document.querySelector<HTMLElement>("#routing-status")!;
  status.textContent = "경로를 찾고 있습니다…";
  const params = new URLSearchParams({
    startLon: String(startPoint[0]), startLat: String(startPoint[1]),
    endLon: String(endPoint[0]), endLat: String(endPoint[1]),
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
    startMarker?.setLngLat(data.waypoints[0].location);
    endMarker?.setLngLat(data.waypoints[1].location);
    status.textContent = "선택 지점 근처 도로 사이의 경로입니다. 지도를 클릭하면 다시 선택합니다.";
  } catch (error) {
    if (request.signal.aborted || pendingRoute !== request) return;
    status.textContent = error instanceof Error ? error.message : "경로 요청에 실패했습니다.";
  } finally {
    if (pendingRoute === request) pendingRoute = null;
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
  pendingRoute?.abort();
  pendingRoute = null;
  document.querySelector<HTMLElement>("#routing-status")!.textContent = "출발점과 도착점을 다시 선택하세요.";
  startMarker?.remove();
  endMarker?.remove();

  startMarker = null;
  endMarker = null;

  startPoint = null;
  endPoint = null;

  if (map.getLayer("route")) {
    map.removeLayer("route");
  }

  if (map.getSource("route")) {
    map.removeSource("route");
  }

  document.querySelector("#route-info")?.classList.add("hidden");
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
  const startWaypoint = data.waypoints[0];
  const endWaypoint = data.waypoints[1];

  const distanceKm = (route.distance / 1000).toFixed(1);

  const durationText = "제공 예정";

  const routeInfo = document.querySelector<HTMLDivElement>("#route-info");
  const startRoad = document.querySelector<HTMLElement>("#start-road");
  const endRoad = document.querySelector<HTMLElement>("#end-road");
  const routeDistance = document.querySelector<HTMLElement>("#route-distance");
  const routeDuration = document.querySelector<HTMLElement>("#route-duration");

  if (
    !routeInfo ||
    !startRoad ||
    !endRoad ||
    !routeDistance ||
    !routeDuration
  ) {
    return;
  }

  startRoad.textContent = startWaypoint.name || "선택 지점 근처 도로";
  endRoad.textContent = endWaypoint.name || "선택 지점 근처 도로";

  routeDistance.textContent = `${distanceKm} km`;
  routeDuration.textContent = durationText;

  routeInfo.classList.remove("hidden");
}
