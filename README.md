# Routing Engine

같은 서울 도로 그래프에서 경로 탐색 알고리즘별 결과·실행 비용을 비교하는 프로젝트.
현재 패널에서 Dijkstra·A*를 선택해 동일한 조건으로 탐색할 수 있다.
Java 21·Spring Boot 백엔드와 TypeScript·Vite·MapLibre 프론트로 구성한다.

## 실행

Java 21, Node.js/npm이 필요하다. 최초 데이터 생성에는 Python 3와 osmium도 필요하다.
그래프가 없으면 [데이터 준비](backend/data/README.md)를 먼저 진행한다.
프로젝트 루트에서 실행한다.

```sh
scripts/run-seoul.sh                 # 백엔드 :8080
```

다른 터미널에서:

```sh
cd frontend
npm ci
npm run dev                         # 프론트 :5173
```

`http://localhost:5173`의 사이드 패널에서 출발지·도착지를 선택하고 지도에 지정한 뒤 **경로 탐색**을 누른다.
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
배경 지도는 OpenFreeMap이며 파란 선은 탐색 결과의 GeoJSON이다.

알고리즘 선택 API는 동일한 진입 간선·회전 이력 탐색을 공유한다. Dijkstra는 남은 비용을 0으로,
A*는 구면거리 하한으로 평가한다. 하한은 간선 비용에 맞춰 보정하며 도로 중간 목적지는 진입 부분 비용을 고려한다.
결과의 좌표 연결 시간, 탐색·경로 복원 시간, 확장 상태 수는 요청별 측정값이다. JSON 변환·통신 시간은 포함하지 않는다.

지도에서 서울 데이터 추출 영역(보라색 점선)과 바깥쪽 음영을 켜고 끌 수 있다.
**영역 전체 보기**로 경계를 확인한다. 행정 경계나 API의 강제 차단 경계는 아니며, 경계 도로 일부는 바깥까지 포함한다.

그래프 파일은 버전·CRC를 검증한다. v1은 노드 회전 제한, v2는 via-way,
v3는 조건부 방향을 추가한다. 서울 시험 그래프는 v2이며 파일 상한은 64MiB다.

## API와 한계

- `GET /api/route?startLon=...&startLat=...&endLon=...&endLat=...&algorithm=dijkstra`
  — `algorithm=dijkstra|astar`(기본 Dijkstra). 경로·거리·연결 좌표·`metrics` 반환. `/route`도 지원한다.
- `GET /api/excluded-roads?west=...&south=...&east=...&north=...`
  — 현재 영역과 경계 상자가 겹치는 제외 도로·사유 반환.
- 경로 오류: 400 좌표·알고리즘 오류, 404 경로 없음, 422 도로에서 50m 초과, 503 그래프 미설정.

현재 거리 기준 탐색이며 예상 시간은 제공하지 않는다. 명시적 자동차 통행 금지는 제외하고,
미해석 규칙은 기록만 남긴다. 도로를 연쇄 제외하지 않으며, 모든 알고리즘은 같은 그래프·규칙을 사용한다.
조건부 일방통행의 제작·시각 고정 탐색은 코어에 구현되어 있지만 HTTP API에는 연결되지 않았다.
지원 시간대는 Asia/Seoul이며 주행 중 시간 변화·조건부 회전 제한은 지원하지 않는다.

## 검증

```sh
./backend/gradlew -p backend test
npm run build --prefix frontend
python3 scripts/test-excluded-export.py
# 서울 서버 실행 후
python3 scripts/check-seoul-api.py
python3 scripts/check-algorithms.py
# Playwright를 사용할 수 있는 Node 환경에서
node scripts/browser-algorithms-check.cjs
node scripts/browser-panel-check.cjs
node scripts/browser-seoul-check.cjs
node scripts/browser-exclusions-check.cjs
```

현재 진행 상태와 다음 작업은 [TODO.md](TODO.md)에서 관리한다.
