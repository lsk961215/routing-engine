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
const $ = <T extends HTMLElement = HTMLElement>(selector: string) => document.querySelector<T>(selector)!;
const initial = examples.find(item => `#${item.id}` === location.hash) ?? examples[0]!;
let selected = initial;
let ready = false;
let markers: Marker[] = [];

$("#demo").innerHTML = `
  <header class="site-header"><div><h1>경로 탐색 비교</h1><p>서울 도로 위에서 Dijkstra와 A*를 공부합니다.</p></div><a href="${repo}">GitHub ↗</a></header>
  <main>
    <section id="explore" aria-label="경로 탐색 실험">
      <div class="toolbar">
        <div class="example-control"><label for="example">경로</label><select id="example">${examples.map(item => `<option value="${item.id}">${item.name}</option>`).join("")}</select></div>
        <div class="visibility-controls"><label><input type="checkbox" id="show-dijkstra" checked><i class="legend-dot blue" aria-hidden="true"></i>Dijkstra</label><label><input type="checkbox" id="show-astar" checked><i class="legend-dot orange" aria-hidden="true"></i>A*</label><label><input id="show-exploration" type="checkbox" checked disabled>탐색 영역</label></div>
      </div>
      <div class="map-panel">
        <div id="demo-map" role="region" aria-label="서울 예제 경로 지도"></div>
        <button id="fit-route" aria-label="탐색 영역 전체 보기" disabled>전체 보기</button>
        <p id="map-status" role="status">지도를 불러오는 중입니다.</p>
      </div>
      <section class="replay-controls" aria-label="탐색 확산 재생">
        <div class="replay-timeline"><button id="trace-play" aria-pressed="false" disabled>재생</button><button id="trace-start" aria-label="탐색 시작으로 이동" disabled>처음</button><input id="trace-position" type="range" min="0" max="1000" value="0" aria-label="탐색 재생 위치" disabled><button id="trace-end" aria-label="탐색 완료로 이동" disabled>끝</button></div>
        <div class="replay-counts"><span><i class="legend-dot blue" aria-hidden="true"></i>Dijkstra <output id="trace-dijkstra-count">—</output></span><span><i class="legend-dot orange" aria-hidden="true"></i>A* <output id="trace-astar-count">—</output></span><output id="trace-count" class="sr-only">지도 준비 중</output></div>
        <p class="replay-note">실제 탐색 기록 · 같은 확장 횟수로 재생하며, 재생 속도는 실행 시간과 다릅니다.</p>
        <p id="trace-status" role="status"></p><button id="trace-retry" hidden>다시 불러오기</button>
      </section>
      <section class="results" aria-label="선택한 경로의 비교 결과">
        <h2 id="route-name" class="sr-only"></h2>
        <table><caption class="sr-only">Dijkstra와 A*의 반복 측정 결과</caption><thead><tr><th scope="col">측정 결과</th><th scope="col"><i class="legend-dot blue" aria-hidden="true"></i>Dijkstra</th><th scope="col"><i class="legend-dot orange" aria-hidden="true"></i>A*</th></tr></thead><tbody>
          <tr><th scope="row">거리 <small>km</small></th><td id="dijkstra-distance"></td><td id="astar-distance"></td></tr>
          <tr><th scope="row">탐색 시간 <small>중앙값 · ms</small></th><td id="dijkstra-time"></td><td id="astar-time"></td></tr>
          <tr><th scope="row">확장 상태 수</th><td id="dijkstra-states"></td><td id="astar-states"></td></tr>
        </tbody></table>
      </section>
    </section>
    <section class="methodology"><details><summary><span class="method-title">측정 조건과 데이터 출처</span></summary><div class="method-content"><p id="method-settings"></p><p>탐색 시간은 탐색과 경로 복원을 포함합니다. 그래프 적재·좌표 연결·HTTP·JSON 변환은 제외합니다. 내려받는 JSON에는 p95와 메모리 할당량도 포함합니다. p95는 측정값의 95%가 이 값 이하인 지점입니다. 메모리는 탐색 중 할당한 힙의 누계이며, 현재·최대 점유량이 아닙니다. 측정 불가 표본은 0으로 바꾸지 않습니다.</p><p>탐색 확산은 성능 측정과 별도로 기록한 실제 상태 확장 순서입니다. 약 120m 격자마다 처음 확장한 실제 노드 하나를 표시하므로 점의 수는 전체 상태 수와 다릅니다. 두 알고리즘에 같은 누적 확장 횟수를 적용하고, 초반 과정을 볼 수 있도록 재생 위치의 세제곱에 비례해 횟수를 늘립니다. 재생 시간은 실행 속도 비교에 사용할 수 없습니다. 최종 경로는 각 알고리즘의 탐색이 완료되면 나타납니다.</p><p>알고리즘의 실행 순서를 교대하고 질의 순서를 섞었습니다. 자연 발생한 GC와 이상값은 포함합니다. 고정 워밍업만으로 JIT 안정화를 보장하지 않으며, 장비와 실행 환경에 따라 시간은 달라집니다.</p><p id="method-environment"></p><p>도로 데이터 © <a href="https://www.openstreetmap.org/copyright">OpenStreetMap 기여자 · ODbL</a>. 배경 지도는 <a href="https://openfreemap.org/">OpenFreeMap</a>을 사용하며 인터넷 연결이 필요합니다. 실제 내비게이션을 위한 서비스가 아닌 알고리즘 학습 프로젝트입니다.</p><div class="method-links"><a id="download-data" download="routing-demo-data.json">예제 경로·측정값 JSON 내려받기 ↓</a><a href="${repo}/blob/main/scripts/benchmark.sh">반복 측정 코드 ↗</a></div></div></details></section>
  </main>
`;

const select = $<HTMLSelectElement>("#example");
select.value = initial.id;
const settings = source.settings;
$("#method-settings").textContent = `측정일 ${source.benchmarkAt.slice(0, 10)} · 독립 JVM ${settings.forks}개 × 질의별 워밍업 ${settings.warmupRounds}회 + 측정 ${settings.measuredRounds}회. 각 예제·알고리즘당 ${settings.forks * settings.measuredRounds}개 표본으로 측정했으며, 표의 시간은 중앙값입니다.`;
$("#method-environment").textContent = `Java ${source.environment.javaVersion} · ${source.environment.os} · ${source.environment.arch} · ${source.environment.jvmArguments.join(" ")} · 엔진 커밋 ${source.gitRevision.slice(0, 7)}`;
const downloadUrl = URL.createObjectURL(new Blob([JSON.stringify(rawData)], { type: "application/json" }));
$<HTMLAnchorElement>("#download-data").href = downloadUrl;

function renderResults() {
  $("#route-name").textContent = selected.name;
  for (const route of selected.routes) {
    const prefix = `#${route.algorithm}`;
    $(`${prefix}-distance`).textContent = number(route.distance / 1000, 2);
    $(`${prefix}-time`).textContent = number(route.searchMillis.median, 2);
    $(`${prefix}-states`).textContent = number(route.expandedStates);
  }

}
renderResults();
setWorkerUrl(workerUrl);
const map = new Map({ container: "demo-map", style: "https://tiles.openfreemap.org/styles/positron", center: [126.978, 37.5665], zoom: 11, localIdeographFontFamily: '"Apple SD Gothic Neo", "Noto Sans CJK KR", sans-serif' });
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
    map.addLayer({ id: algorithm, type: "line", source: algorithm, layout: { "line-join": "round", "line-cap": "round", visibility: "none" }, paint: { "line-color": algorithm === "dijkstra" ? "#2563eb" : "#c65d21", "line-width": algorithm === "dijkstra" ? 7 : 3.5, ...(algorithm === "astar" ? { "line-dasharray": [2, 2] } : {}) } });
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
