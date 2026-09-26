# Routing Engine

같은 서울 도로 그래프에서 경로 탐색 알고리즘별 결과·실행 비용을 비교하는 프로젝트.
현재 패널에서 Dijkstra·A*를 개별 선택하거나 **함께 비교**할 수 있다.
Java 21·Spring Boot 백엔드와 TypeScript·Vite·MapLibre 프론트로 구성한다.

## 실행

Java 21, Node.js/npm, Python 3가 필요하다. 최초 데이터 생성에는 osmium도 필요하다.
그래프가 없으면 [데이터 준비](backend/data/README.md)를 먼저 진행한다.
프로젝트 루트에서 실행한다. 최초 한 번 프론트 의존성을 설치한다.

```sh
npm ci --prefix frontend
./scripts/start.sh                  # 백엔드 :8080 + 프론트 :5173 백그라운드 실행
./scripts/status.sh                 # 실행 상태 확인
./scripts/stop.sh                   # 모두 종료
```

각 서버만 실행·종료하려면 대상을 지정한다.

```sh
./scripts/start.sh backend
./scripts/start.sh frontend
./scripts/stop.sh backend
./scripts/stop.sh frontend
```

백엔드는 실행 전에 JAR를 빌드하고 서울 그래프를 적재한다. HTTP 응답을 확인한 후 실행 완료를 표시한다.
로그와 PID는 `.runtime/`에 저장하며, 로그는 `tail -f .runtime/backend.log .runtime/frontend.log`로 확인한다.
중복 실행은 건너뛰고 종료는 스크립트가 기록한 프로세스에만 적용한다. 기존에 수동으로 실행한 서버는
해당 터미널에서 `Ctrl+C`로 종료한 뒤 스크립트를 사용한다. 종료 후 다시 `start.sh`를 실행하면 변경 사항이 반영된다.
포그라운드 실행은 기존 `scripts/run-seoul.sh`와 `cd frontend && npm run dev`도 사용할 수 있다.
macOS/Linux에서 실행하며 `curl`, `lsof`, `ps`가 필요하다. 서버별 OS 잠금으로 실행·종료·상태 확인의 동시 접근을 막는다.
같은 서버의 다른 명령이 진행 중이면 완료 후 다시 실행하라는 메시지를 표시한다.

`http://localhost:5173`의 사이드 패널에서 출발지·도착지를 선택하고 지도에 지정한 뒤 **경로 탐색**을 누른다.
**함께 비교 → 경로 비교**를 누르면 같은 좌표 연결을 공유하는 두 알고리즘의 거리·탐색/복원 시간·확장 상태 수를 나란히 표시한다.
지도에는 Dijkstra를 파란 실선, A*를 주황 점선으로 표시하며, 결과 표의 체크박스로 각 경로를 켜고 끌 수 있다.
요청 중 **취소**하거나 지점·알고리즘을 변경하면 늦은 응답은 반영하지 않는다. 브라우저 요청 취소가 이미 시작된 서버 계산의 중단까지 보장하지는 않는다.
각 지점 재선택, 출발·도착 교환, 초기화를 패널에서 관리한다. 패널 경계의 화살표로 접거나 펼칠 수 있다. 좌표는 위도·경도 순서이며 선택한 입력 지점을 유지한다. **제외 도로 보기**를 켜고 도로를 클릭하면 제외 사유가 나온다.

## 핵심 구조

- `backend/routing-core`: PBF 분석, 자동차 프로필, 그래프 제작·저장·탐색.
- `backend/routing-api`: 서버 시작 시 그래프·공간 인덱스를 한 번 적재하고 HTTP 요청 처리.
- `frontend`: 배경 지도와 탐색 경로·제외 도로 표시.
- `scripts`: 데이터 제작, 실행, 진단 및 브라우저 검증.

그래프는 OSM 노드의 ID·위경도와 연속 노드 사이의 방향성 간선을 CSR 배열로 저장한다.
간선에는 목적 노드·원본 도로 ID·거리(m)가 있다. 형상용 중간 노드도 유지한다.
탐색은 진입 간선 기반 Dijkstra이며 노드 회전 금지와 via-way 경유 이력을 검사한다.
클릭 좌표는 50m 이내의 가장 가까운 도로 구간에 연결한다. 요청마다 그래프를 복사하지 않는다.
배경 지도는 OpenFreeMap이며 경로는 GeoJSON으로 표시한다.

알고리즘 선택 API는 동일한 진입 간선·회전 이력 탐색을 공유한다. Dijkstra는 남은 비용을 0으로,
A*는 구면거리 하한으로 평가한다. 하한은 간선 비용에 맞춰 보정하며 도로 중간 목적지는 진입 부분 비용을 고려한다.
결과의 좌표 연결 시간, 탐색·경로 복원 시간, 확장 상태 수는 요청별 측정값이다. JSON 변환·통신 시간은 포함하지 않는다.
비교 API는 좌표를 한 번 연결한 뒤 Dijkstra → A* 순서로 실행하며 각 탐색 상태는 독립적이다.
공통 좌표 연결 시간은 한 번만 표시한다. 워밍업·반복 측정을 적용한 벤치마크는 아니므로 단일 요청의 시간만으로 성능을 단정하지 않는다.

지도에서 서울 데이터 추출 영역(보라색 점선)과 바깥쪽 음영을 켜고 끌 수 있다.
**영역 전체 보기**로 경계를 확인한다. 행정 경계나 API의 강제 차단 경계는 아니며, 경계 도로 일부는 바깥까지 포함한다.

그래프 파일은 버전·CRC를 검증한다. v1은 노드 회전 제한, v2는 via-way,
v3는 조건부 방향을 추가한다. 서울 시험 그래프는 v2이며 파일 상한은 64MiB다.

## API와 한계

- `GET /api/route?startLon=...&startLat=...&endLon=...&endLat=...&algorithm=dijkstra`
  — `algorithm=dijkstra|astar`(기본 Dijkstra). 경로·거리·연결 좌표·`metrics` 반환. `/route`도 지원한다.
- `GET /api/compare?startLon=...&startLat=...&endLon=...&endLat=...`
  — 공통 `waypoints`·`snapMillis`와 `results`(Dijkstra, A* 순서)를 반환한다.
  각 결과에는 `algorithm`, `code=Ok|NoRoute`, `routes`, `metrics.searchMillis`, `metrics.expandedStates`가 있다.
  경로가 없어도 HTTP 200으로 `NoRoute`·빈 `routes`·측정값을 반환한다. 좌표 오류·영역 밖·그래프 미설정은 400·422·503이다.
- `GET /api/excluded-roads?west=...&south=...&east=...&north=...`
  — 현재 영역과 경계 상자가 겹치는 제외 도로·사유 반환.
- 경로 오류: 400 좌표·알고리즘 오류, 404 경로 없음, 422 도로에서 50m 초과, 503 그래프 미설정.

현재 거리 기준 탐색이며 예상 시간은 제공하지 않는다. 명시적 자동차 통행 금지는 제외하고,
미해석 규칙은 기록만 남긴다. 도로를 연쇄 제외하지 않으며, 모든 알고리즘은 같은 그래프·규칙을 사용한다.
조건부 일방통행의 제작·시각 고정 탐색은 코어에 구현되어 있지만 HTTP API에는 연결되지 않았다.
지원 시간대는 Asia/Seoul이며 주행 중 시간 변화·조건부 회전 제한은 지원하지 않는다.

## 반복 성능 측정

```sh
./scripts/benchmark.sh
```

서버 실행 없이 전용 JVM에서 서울 고정 질의 8개를 측정한다. 기본 설정은 **독립 JVM 3개 × 질의별 워밍업 4회 + 측정 20회**다.
JVM은 순차 실행하며 각 JVM의 힙은 `-Xms1g -Xmx2g`, GC는 G1이다. 실행 중 다른 부하 작업을 피한다.
질의 좌표는 `backend/data/seoul-benchmark-queries.json`에 고정한다. 질의 순서는 시드로 섞고, 각 질의에서 먼저 실행하는 알고리즘을 매 라운드 교대한다.
짝수 측정 횟수만 허용하므로 알고리즘마다 먼저/나중 실행한 표본 수가 같다. 두 알고리즘은 매번 같은 좌표 연결 결과를 공유한다.

완료 시 출력하는 `.runtime/benchmarks/<실행 ID>/report.html`을 브라우저에서 열면 다음을 확인할 수 있다.

- 질의·알고리즘별 표본 수, 중앙값(p50), p95, 최소·최대, 평균·표준편차.
- JVM별 또는 먼저/나중 실행한 표본만 선택한 통계와 그래프.
- `report.json`: 모든 원시 표본, 질의 좌표, 설정, 그래프/질의 SHA-256, JVM·OS·Git 정보, GC 횟수·시간.
- `fork-1.json` 등: 각 독립 JVM의 측정 결과. 기존 출력 디렉터리를 지정하면 덮어쓰지 않고 실패한다.

`searchMillis`는 탐색·경로 복원만 포함한다. 그래프/인덱스 초기화·좌표 연결·HTTP·JSON·워밍업은 제외한다.
거리·경로 유무·연결 좌표가 두 알고리즘에서 일치하는지 매 쌍 검증하며 불일치하면 중단한다.
자연 발생한 GC와 이상값은 제거하지 않는다. 중앙값은 짝수 표본의 가운데 두 값 평균, p95는 nearest-rank 방식이다.
서로 다른 질의는 하나의 분포로 합치지 않는다. JVM별 편차를 함께 확인하며, 고정 워밍업만으로 JIT 안정화나 통계적 유의성을 보장하지는 않는다.

설정 변경 예시(경로는 `backend/` 기준):

```sh
./scripts/benchmark.sh -PwarmupRounds=6 -PmeasuredRounds=30 -Pforks=3 -Pseed=20260926
./scripts/benchmark.sh -Pqueries=data/seoul-benchmark-queries.json -PbenchmarkReport=../.runtime/benchmarks/my-run
```

기존 `benchmarkRouting`은 이전/현재 구현을 비교하는 진단 도구다. Dijkstra·A* 반복 비교에는 위 스크립트를 사용한다.

## 검증

브라우저 검증 도구는 프로젝트 루트에 별도로 설치한다. 루트 `package-lock.json`으로 Playwright 버전을 고정한다.

```sh
npm ci
npm run browsers:install
```

```sh
./backend/gradlew -p backend test
npm run build --prefix frontend
python3 scripts/test-excluded-export.py
python3 scripts/test-server-lock.py
# 서울 서버 실행 후
python3 scripts/check-seoul-api.py
python3 scripts/check-algorithms.py
# Playwright를 사용할 수 있는 Node 환경에서
node scripts/browser-algorithms-check.cjs
node scripts/browser-comparison-check.cjs
node scripts/browser-panel-check.cjs
node scripts/browser-seoul-check.cjs
node scripts/browser-exclusions-check.cjs
# 반복 측정 보고서 생성 후 통계·필터·모바일 검증
node scripts/check-benchmark-report.cjs .runtime/benchmarks/my-run/report.json
```

현재 진행 상태와 다음 작업은 [TODO.md](TODO.md)에서 관리한다.
