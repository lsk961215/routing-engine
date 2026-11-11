import "@astryxdesign/core/reset.css";
import "@astryxdesign/core/astryx.css";
import "@astryxdesign/theme-neutral/theme.css";
import "maplibre-gl/dist/maplibre-gl.css";
import "./demo.css";
import { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { Theme } from "@astryxdesign/core/theme";
import { neutralTheme } from "@astryxdesign/theme-neutral/built";
import { Button } from "@astryxdesign/core/Button";
import { IconButton } from "@astryxdesign/core/IconButton";
import { Selector } from "@astryxdesign/core/Selector";
import { Switch } from "@astryxdesign/core/Switch";
import { Slider } from "@astryxdesign/core/Slider";
import { Icon } from "@astryxdesign/core/Icon";
import { Popover } from "@astryxdesign/core/Popover";
import { createDemoMap, type Example } from "./demo-map";
import { initialPlayback, type Visibility } from "./demo-exploration";
import { algorithms, algorithmInfo, type Algorithm } from "./demo-algorithms";
import rawData from "./demo-data.json";
import { speedup, reduction as workReduction, formatSpeedup, formatReduction } from "./comparison-metrics";

const examples = rawData.examples as unknown as Example[];
const source = rawData.source;
const repo = "https://github.com/lsk961215/routing-engine";
const number = (n: number, digits = 0) => n.toLocaleString("ko-KR", { maximumFractionDigits: digits });
const initial = examples.find(item => `#${item.id}` === location.hash) ?? examples[0]!;
const options = examples.map(example => ({ value: example.id, label: example.name }));
const settings = source.settings;
const altSource = source.altBenchmark;
const altSettings = altSource.settings;
const landmarkRuns = altSource.landmarks;
const bidirectionalSource = source.bidirectionalBenchmark;
const reverseRuns = bidirectionalSource.reverseIndexes;

function PlaybackIcon({ kind }: { kind: "play" | "pause" | "replay" | "start" | "end" }) {
  return <svg className="playback-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
    {kind === "play" && <path d="M8 5.5 18 12 8 18.5Z" fill="currentColor" stroke="currentColor" strokeLinejoin="round" />}
    {kind === "pause" && <><rect x="6.5" y="5" width="4" height="14" rx="1" fill="currentColor" /><rect x="13.5" y="5" width="4" height="14" rx="1" fill="currentColor" /></>}
    {kind === "replay" && <path d="M5 9a8 8 0 1 1-.5 5M5 4v5h5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />}
    {kind === "start" && <><path d="m17 6-8 6 8 6Z" fill="currentColor" /><path d="M6 5v14" stroke="currentColor" strokeWidth="2" strokeLinecap="round" /></>}
    {kind === "end" && <><path d="m7 6 8 6-8 6Z" fill="currentColor" /><path d="M18 5v14" stroke="currentColor" strokeWidth="2" strokeLinecap="round" /></>}
  </svg>;
}

function AlgorithmHelp({ algorithm }: { algorithm: Algorithm }) {
  const [isOpen, setIsOpen] = useState(false);
  const info = algorithmInfo[algorithm];
  return <Popover label={`${info.name} 알고리즘 설명`} isOpen={isOpen} onOpenChange={setIsOpen}
    placement="below" alignment="end" width={380} isModal={false} closeButtonLabel="설명 닫기"
    content={<div className="algorithm-explanation">
      <div className="explanation-heading"><h3>{info.name}</h3><Button label="닫기" variant="ghost" size="sm" onClick={() => setIsOpen(false)} /></div>
      <dl>
        <div><dt>작은 값부터 탐색</dt><dd>{info.priority}</dd></div>
        <div><dt>동작 방식</dt><dd>{info.explanation}</dd></div>
        <div><dt>특징과 비용</dt><dd>{info.tradeoff}</dd></div>
      </dl>
      <p>이 데모의 비용은 거리입니다. 네 알고리즘 모두 같은 도로·통행 규칙에서 최단 거리를 구하며, 거리가 같아도 경로 모양은 다를 수 있습니다.</p>
    </div>}>
    {trigger => <button {...trigger} type="button" className="algorithm-help" aria-label={`${info.name} 알고리즘 설명`}>
      <svg viewBox="0 0 20 20" aria-hidden="true"><circle cx="10" cy="10" r="7.5" /><path d="M10 9v5" /><circle cx="10" cy="6" r=".8" className="info-dot" /></svg>
    </button>}
  </Popover>;
}

function Demo() {
  const [selected, setSelected] = useState(initial);
  const [ready, setReady] = useState(false);
  const [mapStatus, setMapStatus] = useState("지도를 불러오는 중입니다.");
  const [playback, setPlayback] = useState(initialPlayback);
  const [visibility, setVisibility] = useState<Visibility>({ dijkstra: true, astar: true, alt: true, bidirectional: true, exploration: true });
  const [downloadUrl, setDownloadUrl] = useState("");
  const mapElement = useRef<HTMLDivElement>(null);
  const controller = useRef<ReturnType<typeof createDemoMap> | null>(null);
  const canPlay = playback.status === "ready";
  const astar = selected.routes.find(route => route.algorithm === "astar")!;
  const baseline = selected.routes.find(route => route.algorithm === "dijkstra")!;
  const alt = selected.routes.find(route => route.algorithm === "alt")!;
  const reduction = (1 - alt.expandedStates / astar.expandedStates) * 100;
  const maxStates = Math.max(...selected.routes.map(route => route.expandedStates));

  useEffect(() => {
    const map = createDemoMap(mapElement.current!, initial, source.graphSha256, {
      onReady: () => { setReady(true); setMapStatus(""); },
      onError: setMapStatus, onPlayback: setPlayback,
    });
    controller.current = map;
    const url = URL.createObjectURL(new Blob([JSON.stringify(rawData)], { type: "application/json" }));
    setDownloadUrl(url);
    function onHashChange() {
      const example = examples.find(item => `#${item.id}` === location.hash);
      if (example) { setSelected(example); map.select(example); }
    }
    window.addEventListener("hashchange", onHashChange);
    return () => {
      window.removeEventListener("hashchange", onHashChange);
      URL.revokeObjectURL(url);
      map.destroy();
      controller.current = null;
    };
  }, []);

  function chooseExample(id: string) {
    const example = examples.find(item => item.id === id)!;
    setSelected(example);
    history.replaceState(null, "", `#${id}`);
    controller.current?.select(example);
  }
  function toggleVisibility(key: keyof Visibility, value: boolean) {
    const next = { ...visibility, [key]: value };
    setVisibility(next);
    controller.current?.exploration.setVisibility(next);
  }
  const playLabel = playback.playing ? "일시정지" : playback.progress >= 1 ? "처음부터 재생" : "재생";
  return <Theme theme={neutralTheme} mode="light">
    <div className="demo-shell">
      <a className="skip-link" href="#explore">탐색 실험으로 바로 가기</a>
      <header className="site-header">
        <div className="brand"><span className="brand-mark" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M5 17V7h7v10h7V7"/><circle cx="5" cy="17" r="2"/><circle cx="19" cy="7" r="2"/></svg></span><div><h1>경로 탐색 비교</h1><p>서울 도로 위에서 배우는 Dijkstra · A* · ALT · 양방향 Dijkstra</p></div></div>
        <Button label="GitHub" href={repo} variant="ghost" size="sm" endContent={<Icon icon="externalLink" />} />
      </header>
      <main id="explore" tabIndex={-1}>
        <section className="workspace" aria-label="경로 탐색 실험">
          <div className="toolbar">
            <Selector id="example" htmlName="example" label="예제 경로" value={selected.id} options={options} onChange={chooseExample} width="100%" size="lg" containerClassName="example-control" presentation="adaptive" />
            <div className="visibility-controls" aria-label="지도 표시">
              {algorithms.map(algorithm => <div key={algorithm} className={`algorithm-toggle ${algorithmInfo[algorithm].colorClass}`}>
                <Switch id={`show-${algorithm}`} label={algorithmInfo[algorithm].label} size="sm" value={visibility[algorithm]} onChange={value => toggleVisibility(algorithm, value)} />
                <AlgorithmHelp algorithm={algorithm} />
              </div>)}
              <span className="area-toggle"><Switch id="show-exploration" label="탐색 영역" size="sm" value={visibility.exploration} isDisabled={!canPlay} onChange={value => toggleVisibility("exploration", value)} /></span>
            </div>
          </div>
          <div className="experiment-grid">
            <div className="map-workspace">
              <div className="map-panel">
                <div id="demo-map" ref={mapElement} role="region" aria-label="서울 예제 경로 지도" />
                <div className="map-fit"><Button id="fit-route" label="전체 보기" aria-label="탐색 영역 전체 보기" isDisabled={!ready} onClick={() => controller.current?.fit()} size="sm" elevation="low" /></div>
                <p id="map-status" role="status" hidden={!mapStatus}>{mapStatus}</p>
              </div>
              <section className="replay-controls" aria-label="탐색 확산 재생">
                <div className="replay-timeline">
                  <div className="playback-buttons" role="group" aria-label="재생 조작">
                    <IconButton id="trace-start" label="탐색 시작으로 이동" tooltip={canPlay ? "처음으로" : undefined} icon={<PlaybackIcon kind="start" />} variant="ghost" isDisabled={!canPlay} onClick={() => controller.current?.exploration.seek(0)} />
                    <IconButton id="trace-play" className="playback-primary" label={playLabel} tooltip={canPlay ? playLabel : undefined} icon={<PlaybackIcon kind={playback.playing ? "pause" : playback.progress >= 1 ? "replay" : "play"} />} variant="primary" isDisabled={!canPlay} aria-pressed={playback.playing} onClick={() => controller.current?.exploration.toggle()} />
                    <IconButton id="trace-end" label="탐색 완료로 이동" tooltip={canPlay ? "끝으로" : undefined} icon={<PlaybackIcon kind="end" />} variant="ghost" isDisabled={!canPlay} onClick={() => controller.current?.exploration.seek(1)} />
                  </div>
                  <Slider id="trace-position" label="탐색 재생 위치" isLabelHidden valueDisplay="none" value={Math.round(playback.progress * 1000)} min={0} max={1000} step={1} isDisabled={!canPlay} onChange={(value: number) => controller.current?.exploration.seek(value / 1000)} formatValue={value => `누적 확장 ${number(Math.floor(maxStates * (value / 1000) ** 3))}회`} containerClassName="timeline-slider" />
                </div>
                <div className="replay-caption"><output id="trace-count">같은 확장 횟수 {number(playback.step)}회</output><span>{playback.playing ? "재생 중" : playback.progress >= 1 ? "탐색 완료" : "처음부터 살펴보세요"}</span></div>
                <p className="replay-note">진한 점은 최근 탐색, 옅은 점은 누적 탐색입니다. 재생 속도는 실행 시간과 다릅니다.</p>
                <p id="trace-status" role="status" hidden={!playback.message}>{playback.message}</p>
                <Button id="trace-retry" label="다시 불러오기" hidden={playback.status !== "error"} onClick={() => controller.current?.exploration.retry()} size="sm" />
              </section>
            </div>
            <aside className="results" aria-label="선택한 경로의 비교 결과">
              <div className="results-heading"><h2>알고리즘 비교</h2><span>실제 측정값</span></div>
              <h3 id="route-name" className="sr-only">{selected.name}</h3>
              {selected.routes.map(route => {
                const info = algorithmInfo[route.algorithm];
                const name = info.name;
                const count = playback.counts[route.algorithm];
                return <section key={route.algorithm} className={`algorithm-card ${route.algorithm}`} aria-label={`${name} 측정 결과`}>
                  <div className="algorithm-heading"><h3><i className={`legend-dot ${info.colorClass}`} />{name}</h3><span>{info.description}</span></div>
                  <div className="time-metric"><strong id={`${route.algorithm}-time`}>{number(route.searchMillis.median, 2)}</strong><span>ms</span></div>
                  <p className="metric-label">탐색 시간 · 중앙값{route.algorithm === "alt" || route.algorithm === "bidirectional" ? " · 준비 비용 제외" : ""}</p>
                  <dl className="secondary-metrics"><div><dt>거리</dt><dd><span id={`${route.algorithm}-distance`}>{number(route.distance / 1000, 2)}</span> <small>km</small></dd></div><div><dt>확장 상태</dt><dd id={`${route.algorithm}-states`}>{number(route.expandedStates)}</dd></div></dl>
                  <div className="expansion-bar" aria-hidden="true"><i style={{ width: `${route.expandedStates / maxStates * 100}%` }} /></div>
                  <output id={`trace-${route.algorithm}-count`} className="trace-count">{count ? `${number(count.current)} / ${number(count.total)}${count.current === count.total ? " · 완료" : ""}` : "—"}</output>
                  <details className="work-metrics"><summary>작업량과 기준 비교</summary>
                    <dl className="secondary-metrics">
                      <div><dt>간선 검사</dt><dd id={`${route.algorithm}-edges`}>{number(route.inspectedEdges)}</dd></div>
                      <div><dt>최대 큐 크기</dt><dd id={`${route.algorithm}-queue`}>{number(route.maxQueueSize)}</dd></div>
                      <div><dt>시간 배율</dt><dd id={`${route.algorithm}-speedup`}>{route.algorithm === "dijkstra" ? "기준" : formatSpeedup(speedup(route.baselineSearchMillis.median, route.searchMillis.median))}</dd></div>
                      <div><dt>간선 검사 감소율</dt><dd id={`${route.algorithm}-reduction`}>{route.algorithm === "dijkstra" ? "기준" : formatReduction(workReduction(baseline.inspectedEdges, route.inspectedEdges))}</dd></div>
                    </dl>
                    <p className="metric-label">Dijkstra 기준 · 1× 미만 또는 음수는 성능 저하</p>
                  </details>
                </section>;
              })}
              <p className="comparison-note">{reduction === 0 ? "A*와 ALT의 확장 상태 수가 같습니다." : <>ALT는 A*보다 확장 상태가 <strong>{number(Math.abs(reduction), 1)}% {reduction > 0 ? "적습니다" : "많습니다"}.</strong></>}<span>막대 길이는 최종 확장 상태 수입니다.</span></p>
            </aside>
          </div>
        </section>

        {/* Measurement details stay available without competing with the experiment. */}
        <section className="methodology"><details><summary><span className="method-title">측정 조건과 데이터 출처</span></summary><div className="method-content"><p className="comparison-note">시간 배율은 각 알고리즘과 함께 측정한 Dijkstra 중앙값을 기준으로 계산합니다. 간선 검사는 제한으로 제외된 후보를 포함하며 초기 출발 후보는 제외합니다. 최대 큐 크기는 오래된 항목을 포함한 큐 항목 수로, 메모리 바이트가 아닙니다.</p><p id="method-settings">측정일 {source.benchmarkAt.slice(0, 10)} · 독립 JVM {settings.forks}개 × 질의별 워밍업 {settings.warmupRounds}회 + 측정 {settings.measuredRounds}회. Dijkstra/A*는 예제·알고리즘당 {settings.forks * settings.measuredRounds}개 표본이며, 시간은 중앙값입니다.</p><p id="alt-method-settings">ALT 측정일 {altSource.benchmarkAt.slice(0, 10)} · 독립 JVM {altSettings.forks}개 × 워밍업 {altSettings.warmupRounds}회 + 측정 {altSettings.measuredRounds}회, 예제당 {altSettings.forks * altSettings.measuredRounds}개 표본입니다. Dijkstra/A*와 Dijkstra/ALT는 같은 그래프·질의·설정으로 별도 실행한 비교이며, 화면의 Dijkstra 값은 Dijkstra/A* 실행에서 가져왔습니다. 실행 시점 차이가 있어 시간 차이를 알고리즘 효과만으로 단정할 수 없습니다.</p><p id="bidirectional-method">양방향 Dijkstra 측정일 {bidirectionalSource.benchmarkAt.slice(0, 10)} · 독립 JVM {bidirectionalSource.settings.forks}개 × 워밍업 {bidirectionalSource.settings.warmupRounds}회 + 측정 {bidirectionalSource.settings.measuredRounds}회입니다. 별도 실행에서 함께 측정한 Dijkstra를 시간 배율의 기준으로 사용합니다. 역방향 연결 인덱스 준비 {number(Math.min(...reverseRuns.map(run => run.preprocessingMillis)), 1)}–{number(Math.max(...reverseRuns.map(run => run.preprocessingMillis)), 1)}ms · 배열 {number(reverseRuns[0]!.arrayPayloadBytes / 1048576, 1)}MiB이며 객체·Map·임시 메모리는 제외합니다. 인덱스 준비는 질의 시간·할당량에 포함하지 않습니다. 확장·간선 검사 횟수는 양쪽 합계, 최대 큐 크기는 두 큐에 동시에 저장된 항목 수의 합계입니다.</p><p id="landmark-method">ALT는 기준점까지의 거리를 미리 계산해 탐색에 활용합니다. 랜드마크 {landmarkRuns[0]!.count}개 · 거리 테이블 {number(landmarkRuns[0]!.distanceTableBytes / 1048576, 1)}MiB · JVM별 전처리 {number(Math.min(...landmarkRuns.map(run => run.preprocessingMillis)) / 1000, 2)}–{number(Math.max(...landmarkRuns.map(run => run.preprocessingMillis)) / 1000, 2)}초입니다. 표의 ALT 탐색 시간·할당량은 전처리를 제외합니다. 거리 테이블 크기는 객체 부가 비용·임시 메모리를 제외한 값입니다.</p><p>탐색 시간은 탐색과 경로 복원을 포함합니다. 그래프 적재·좌표 연결·HTTP·JSON 변환은 제외합니다. 내려받는 JSON에는 p95와 메모리 할당량도 포함합니다. p95는 측정값의 95%가 이 값 이하인 지점입니다. 메모리는 탐색 중 할당한 힙의 누계이며, 현재·최대 점유량이 아닙니다. 측정 불가 표본은 0으로 바꾸지 않습니다.</p><p>탐색 확산은 성능 측정과 별도로 기록한 실제 상태 확장 순서입니다. 약 120m 격자마다 처음 확장한 실제 노드 하나를 표시하므로 점의 수는 전체 상태 수와 다릅니다. 점 크기는 지도 축척에 맞추며 네 알고리즘에 같은 크기를 적용합니다. 진한 점은 최근 확장한 위치이고, 완료된 탐색은 누적 영역만 표시합니다. 도로 연결과 통행 규칙을 따라 탐색하므로 원형으로 퍼지거나 모든 점이 이어져 보이지는 않습니다. 네 알고리즘에 같은 누적 확장 횟수를 적용하고, 초반 과정을 볼 수 있도록 재생 위치의 세제곱에 비례해 횟수를 늘립니다. 재생 시간은 실행 속도 비교에 사용할 수 없습니다. 최종 경로는 각 알고리즘의 탐색이 완료되면 나타납니다.</p><p>알고리즘의 실행 순서를 교대하고 질의 순서를 섞었습니다. 자연 발생한 GC와 이상값은 포함합니다. 고정 워밍업만으로 JIT 안정화를 보장하지 않으며, 장비와 실행 환경에 따라 시간은 달라집니다.</p><p id="method-environment">Java {source.environment.javaVersion} · {source.environment.os} · {source.environment.arch} · {source.environment.jvmArguments.join(" ")} · 엔진 기준 커밋 {source.gitRevision.slice(0, 7)}{source.gitDirty || altSource.gitDirty || bidirectionalSource.gitDirty ? " · 커밋되지 않은 엔진 변경 포함" : ""}</p><p>도로 데이터 © <a href="https://www.openstreetmap.org/copyright">OpenStreetMap 기여자 · ODbL</a>. 배경 지도는 <a href="https://openfreemap.org/">OpenFreeMap</a>을 사용하며 인터넷 연결이 필요합니다. 실제 내비게이션을 위한 서비스가 아닌 알고리즘 학습 프로젝트입니다.</p><div className="method-links"><a href={downloadUrl} id="download-data" download="routing-demo-data.json">예제 경로·측정값 JSON 내려받기 ↓</a><a href={`${repo}/blob/main/scripts/benchmark.sh`}>반복 측정 코드 ↗</a></div></div></details></section>
      </main>
      <footer><span>Routing Engine · 서울 경로 {examples.length}개</span><span>같은 도로, 같은 통행 규칙으로 비교합니다.</span></footer>
    </div>
  </Theme>;
}

createRoot(document.getElementById("demo")!).render(<Demo />);
