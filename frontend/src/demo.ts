import "maplibre-gl/dist/maplibre-gl.css";
import "./demo.css";
import { Map, Marker, NavigationControl, LngLatBounds, setWorkerUrl } from "maplibre-gl";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";
import rawData from "./demo-data.json";
import { setupExploration } from "./demo-exploration";

type Algorithm = "dijkstra" | "astar";
interface Distribution { count: number; median: number; p95: number }
interface DemoRoute {
  algorithm: Algorithm;
  distance: number;
  geometry: { type: "LineString"; coordinates: [number, number][] };
  searchMillis: Distribution;
  expandedStates: number;
  allocatedBytes: Distribution | null;
}
interface Example { id: string; name: string; start: [number, number]; end: [number, number]; routes: DemoRoute[] }
// The exporter validates geometries; JSON inference widens coordinate tuples to arrays.
const examples = rawData.examples as unknown as Example[];
const source = rawData.source;
const repo = "https://github.com/lsk961215/routing-engine";
const number = (n: number, digits = 0) => n.toLocaleString("ko-KR", { maximumFractionDigits: digits });
const allocation = (d: Distribution | null) => d ? `${number(d.median / 1048576, 2)} MiB` : "측정 불가";
const $ = <T extends HTMLElement = HTMLElement>(selector: string) => document.querySelector<T>(selector)!;
const initial = examples.find(item => `#${item.id}` === location.hash) ?? examples[0]!;
let selected = initial;
let ready = false;
let markers: Marker[] = [];

$("#demo").innerHTML = `
  <header class="site-header"><a class="brand" href="${import.meta.env.BASE_URL}"><span class="brand-symbol" aria-hidden="true">↗</span>Routing Engine<span class="brand-note">LEARNING PROJECT</span></a><a class="source-link" href="${repo}">GitHub 코드 보기 <span aria-hidden="true">↗</span></a></header>
  <main>
    <section class="intro" aria-labelledby="title">
      <div><p class="eyebrow">서울 도로로 공부하는 알고리즘</p><h1 id="title">같은 목적지,<br>다른 탐색 과정.</h1><p class="intro-copy">도로 데이터를 그래프로 만들고, Dijkstra와 A*를 직접 구현했습니다.<br class="desktop-break"> 두 색으로 퍼지는 탐색 영역을 살펴보고, 두 알고리즘이 들인 비용을 비교해 보세요.</p></div>
      <div class="intro-note"><span class="note-dot"></span><strong>실제 엔진으로 계산한 예제</strong><p>실제 탐색 순서와 경로, 반복 측정 결과를 보여줍니다.<br>지금 브라우저에서 경로를 계산하는 것은 아닙니다.</p><a href="#how-it-works">어떻게 만들었나요? <span aria-hidden="true">↓</span></a></div>
    </section>
    <section class="explorer" aria-labelledby="explore-title">
      <div class="explorer-heading"><div><p class="eyebrow">01 / TRY THE ROUTES</p><h2 id="explore-title">서울의 경로를 비교해 보세요</h2></div><div class="example-control"><label for="example">예제 경로</label><select id="example">${examples.map(item => `<option value="${item.id}">${item.name}</option>`).join("")}</select></div></div>
      <section class="replay-controls" aria-label="탐색 확산 재생">
        <div class="replay-heading"><strong>탐색이 퍼지는 모습</strong><label><input id="show-exploration" type="checkbox" checked disabled>탐색 영역 표시</label><span>파란 점 Dijkstra · 주황 점 A*</span></div>
        <div class="replay-timeline"><button id="trace-play" aria-pressed="false" disabled>처음부터 재생</button><button id="trace-start" disabled>처음</button><input id="trace-position" type="range" min="0" max="1000" value="1000" aria-label="탐색 재생 위치" disabled><button id="trace-end" disabled>끝</button></div>
        <div class="replay-counts"><output id="trace-count">지도 준비 중</output><span><i class="route-line blue"></i>Dijkstra <output id="trace-dijkstra-count">—</output></span><span><i class="route-line orange"></i>A* <output id="trace-astar-count">—</output></span></div>
        <p class="replay-note">같은 확장 횟수 기준 · 초반은 천천히 재생 · 실제 실행 시간이 아닙니다. 최종 경로는 각 탐색이 끝나면 나타납니다.</p>
        <p id="trace-status" role="status"></p><button id="trace-retry" hidden>탐색 기록 다시 불러오기</button>
      </section>
      <div class="map-panel"><div id="demo-map" role="region" aria-label="서울 예제 경로 지도"></div><div class="map-tools"><span><i class="route-line blue"></i>Dijkstra</span><span><i class="route-line orange"></i>A*</span><button id="fit-route" disabled>탐색 영역 전체 보기</button></div><p id="map-status" role="status">배경 지도를 불러오는 중입니다. 측정 결과는 바로 확인할 수 있어요.</p></div>
      <aside class="results" aria-label="선택한 경로의 비교 결과"><div class="result-title"><span class="eyebrow">SAME GRAPH · SAME COST</span><h3 id="route-name"></h3><p>같은 도로·통행 규칙에서 거리 기준으로 탐색</p></div>
        <table><caption class="sr-only">Dijkstra와 A*의 반복 측정 결과</caption><thead><tr><th scope="col">측정 항목</th><th scope="col"><i class="route-line blue"></i>Dijkstra</th><th scope="col"><i class="route-line orange"></i>A*</th></tr></thead><tbody>
          <tr><th scope="row">최단 거리<small>km</small></th><td id="dijkstra-distance"></td><td id="astar-distance"></td></tr>
          <tr><th scope="row">탐색 시간<small>중앙값 · ms</small></th><td id="dijkstra-time"></td><td id="astar-time"></td></tr>
          <tr><th scope="row">탐색 시간<small>p95 · ms</small></th><td id="dijkstra-p95"></td><td id="astar-p95"></td></tr>
          <tr><th scope="row">확장 상태 수</th><td id="dijkstra-states"></td><td id="astar-states"></td></tr>
          <tr><th scope="row">메모리 할당량<small>중앙값</small></th><td id="dijkstra-memory"></td><td id="astar-memory"></td></tr>
        </tbody></table>
        <div class="visibility-controls"><label><input type="checkbox" id="show-dijkstra" checked><i class="route-line blue"></i>Dijkstra 표시</label><label><input type="checkbox" id="show-astar" checked><i class="route-line orange"></i>A* 표시</label></div>
        <p class="map-hint">알고리즘별 탐색 영역과 경로를 함께 켜고 끕니다.</p>
        <div class="insight" aria-live="polite"><span>이번 예제에서</span><p id="insight"></p><div class="bar-row"><span>Dijkstra</span><div><i id="dijkstra-bar"></i></div></div><div class="bar-row"><span>A*</span><div><i id="astar-bar"></i></div></div><small>막대 길이 = 확장 상태 수</small></div>
        <p class="sample-note" id="sample-note"></p>
      </aside>
    </section>
    <section class="explanation" aria-labelledby="read-title"><div><p class="eyebrow">02 / READ THE DIFFERENCE</p><h2 id="read-title">거리가 같아도,<br>탐색량은 다를 수 있습니다.</h2></div><div class="explanation-copy"><p><strong>Dijkstra</strong>는 출발점부터 지금까지의 비용이 작은 상태를 먼저 탐색합니다. <strong>A*</strong>는 여기에 목적지까지 남은 거리의 하한을 더해 탐색 순서를 정합니다.</p><p>두 알고리즘은 같은 최단 거리를 구하지만 경로 모양은 다를 수 있습니다. 이 구현은 일방통행과 회전 제한을 함께 다루므로, 단순한 교차점 수 대신 <strong>진입 간선과 회전 이력을 포함한 상태 수</strong>를 비교합니다.</p></div></section>
    <section id="how-it-works" class="build-section" aria-labelledby="build-title"><p class="eyebrow">03 / WHAT I BUILT</p><h2 id="build-title">데이터부터 화면까지, 직접 연결해 본 과정</h2><ol class="pipeline"><li><span>01</span><h3>도로 데이터</h3><p>OpenStreetMap에서 서울 도로와 자동차 통행 규칙을 추출했습니다.</p><a href="${repo}/blob/main/backend/data/README.md">데이터 준비 ↗</a></li><li><span>02</span><h3>그래프와 탐색</h3><p>CSR 배열에 방향성 간선을 저장하고, Dijkstra와 A*의 탐색 로직을 구현했습니다.</p><a href="${repo}/tree/main/backend/routing-core">Java 코어 ↗</a></li><li><span>03</span><h3>API와 지도</h3><p>클릭 좌표를 도로에 연결하고, Spring Boot 응답을 MapLibre 지도에 표시했습니다.</p><a href="${repo}/tree/main/backend/routing-api">API 구현 ↗</a></li><li><span>04</span><h3>측정과 실행</h3><p>독립 JVM에서 반복 측정하고, Docker Compose로 API와 Nginx 실행을 묶었습니다.</p><a href="${repo}/blob/main/compose.yaml">실행 구성 ↗</a></li></ol></section>
    <section class="methodology"><details><summary>측정 조건과 데이터 출처 <span>결과를 읽을 때 알아두면 좋아요</span></summary><div class="method-content"><p id="method-settings"></p><p>탐색 시간은 탐색과 경로 복원을 포함합니다. 그래프 적재·좌표 연결·HTTP·JSON 변환은 제외합니다. p95는 측정값의 95%가 이 값 이하인 지점입니다. 메모리는 탐색 중 할당한 힙의 누계이며, 현재·최대 점유량이 아닙니다. 측정 불가 표본은 0으로 바꾸지 않습니다.</p><p>탐색 확산은 성능 측정과 별도로 기록한 실제 상태 확장 순서입니다. 약 120m 격자마다 처음 확장한 실제 노드 하나를 표시하므로 점의 수는 전체 상태 수와 다릅니다. 두 알고리즘에 같은 누적 확장 횟수를 적용하고, 초반 과정을 볼 수 있도록 재생 위치의 세제곱에 비례해 횟수를 늘립니다. 재생 시간은 실행 속도 비교에 사용할 수 없습니다.</p><p>알고리즘의 실행 순서를 교대하고 질의 순서를 섞었습니다. 자연 발생한 GC와 이상값은 포함합니다. 고정 워밍업만으로 JIT 안정화를 보장하지 않으며, 장비와 실행 환경에 따라 시간은 달라집니다.</p><p id="method-environment"></p><p>도로 데이터 © <a href="https://www.openstreetmap.org/copyright">OpenStreetMap 기여자 · ODbL</a>. 배경 지도는 <a href="https://openfreemap.org/">OpenFreeMap</a>을 사용하며 인터넷 연결이 필요합니다. 실제 내비게이션을 위한 서비스가 아닌 알고리즘 학습 프로젝트입니다.</p><a id="download-data" download="routing-demo-data.json">예제 경로·측정값 JSON 내려받기 ↓</a><a href="${repo}/blob/main/scripts/benchmark.sh">반복 측정 코드 ↗</a></div></details></section>
  </main><footer><span>Routing Engine · 만든 과정을 기록하는 학습 프로젝트</span><a href="${repo}">소스와 실행 방법은 GitHub에서 ↗</a></footer>
`;

const select = $<HTMLSelectElement>("#example");
select.value = initial.id;
const settings = source.settings;
$("#method-settings").textContent = `측정일 ${source.benchmarkAt.slice(0, 10)} · 독립 JVM ${settings.forks}개 × 질의별 워밍업 ${settings.warmupRounds}회 + 측정 ${settings.measuredRounds}회. 각 예제·알고리즘당 ${settings.forks * settings.measuredRounds}개 표본의 중앙값과 p95입니다.`;
$("#method-environment").textContent = `Java ${source.environment.javaVersion} · ${source.environment.os} · ${source.environment.arch} · ${source.environment.jvmArguments.join(" ")} · 엔진 커밋 ${source.gitRevision.slice(0, 7)}`;
const downloadUrl = URL.createObjectURL(new Blob([JSON.stringify(rawData)], { type: "application/json" }));
$<HTMLAnchorElement>("#download-data").href = downloadUrl;

function renderResults() {
  $("#route-name").textContent = selected.name;
  for (const route of selected.routes) {
    const prefix = `#${route.algorithm}`;
    $(`${prefix}-distance`).textContent = number(route.distance / 1000, 2);
    $(`${prefix}-time`).textContent = number(route.searchMillis.median, 2);
    $(`${prefix}-p95`).textContent = number(route.searchMillis.p95, 2);
    $(`${prefix}-states`).textContent = number(route.expandedStates);
    $(`${prefix}-memory`).textContent = allocation(route.allocatedBytes);
  }
  const dijkstra = selected.routes.find(r => r.algorithm === "dijkstra")!;
  const astar = selected.routes.find(r => r.algorithm === "astar")!;
  const reduction = (1 - astar.expandedStates / dijkstra.expandedStates) * 100;
  $("#insight").textContent = reduction >= 0 ? `A*의 확장 상태 수가 ${number(reduction, 1)}% 적습니다.` : `A*의 확장 상태 수가 ${number(-reduction, 1)}% 많습니다.`;
  const max = Math.max(dijkstra.expandedStates, astar.expandedStates, 1);
  for (const route of selected.routes) $(`#${route.algorithm}-bar`).style.width = `${route.expandedStates / max * 100}%`;
  $("#sample-note").textContent = `알고리즘별 ${dijkstra.searchMillis.count}회 반복 측정 · 저장된 결과\n할당량 유효 표본: Dijkstra ${dijkstra.allocatedBytes?.count ?? 0} / A* ${astar.allocatedBytes?.count ?? 0}`;
}
renderResults();
setWorkerUrl(workerUrl);
const map = new Map({ container: "demo-map", style: "https://tiles.openfreemap.org/styles/bright", center: [126.978, 37.5665], zoom: 11, localIdeographFontFamily: '"Apple SD Gothic Neo", "Noto Sans CJK KR", sans-serif' });
map.addControl(new NavigationControl({ showCompass: false }), "top-right");
const exploration = setupExploration(map, source.graphSha256, fitRoute);
function fitRoute() {
  const bounds = new LngLatBounds();
  for (const route of selected.routes) for (const coordinate of route.geometry.coordinates) bounds.extend(coordinate);
  for (const coordinate of exploration.bounds()) bounds.extend(coordinate);
  bounds.extend(selected.start).extend(selected.end);
  map.fitBounds(bounds, { padding: 60, maxZoom: 14, duration: 0 });
}
function drawRoutes() {
  if (!ready) return;
  for (const algorithm of ["dijkstra", "astar"] as const) {
    if (map.getLayer(algorithm)) map.removeLayer(algorithm);
    if (map.getSource(algorithm)) map.removeSource(algorithm);
    const route = selected.routes.find(r => r.algorithm === algorithm)!;
    map.addSource(algorithm, { type: "geojson", data: { type: "Feature", properties: {}, geometry: route.geometry } });
    map.addLayer({ id: algorithm, type: "line", source: algorithm, layout: { "line-join": "round", "line-cap": "round", visibility: $<HTMLInputElement>(`#show-${algorithm}`).checked ? "visible" : "none" }, paint: { "line-color": algorithm === "dijkstra" ? "#2563eb" : "#c65d21", "line-width": algorithm === "dijkstra" ? 7 : 3.5, ...(algorithm === "astar" ? { "line-dasharray": [2, 2] } : {}) } });
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
  fitRoute();
}
function chooseExample() {
  selected = examples.find(item => item.id === select.value)!;
  history.replaceState(null, "", `#${selected.id}`);
  renderResults();
  drawRoutes();
}
select.addEventListener("change", chooseExample);
window.addEventListener("hashchange", () => {
  const example = examples.find(item => `#${item.id}` === location.hash);
  if (!example) return;
  select.value = example.id;
  chooseExample();
});
for (const algorithm of ["dijkstra", "astar"] as const) $(`#show-${algorithm}`).addEventListener("change", () => {
  if (ready) exploration.updateVisibility();
});
$("#fit-route").addEventListener("click", fitRoute);
map.on("load", () => { ready = true; $("#map-status").textContent = ""; $<HTMLButtonElement>("#fit-route").disabled = false; drawRoutes(); });
map.on("error", () => { $("#map-status").textContent = "배경 지도를 불러오지 못했습니다. 인터넷 연결을 확인해 주세요. 저장된 측정 결과는 계속 볼 수 있습니다."; });
new ResizeObserver(() => { map.resize(); if (ready) fitRoute(); }).observe($(".map-panel"));
