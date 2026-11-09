# Routing Engine

같은 서울 도로 그래프에서 경로 탐색 알고리즘별 결과·실행 비용을 비교하는 프로젝트.
엔진과 API는 Dijkstra·A*·랜드마크 A*(ALT)를 지원한다. 현재 패널에서는 Dijkstra·A*를 개별 선택하거나 **함께 비교**할 수 있다.
Java 21·Spring Boot 백엔드와 TypeScript·Vite·MapLibre 프론트로 구성한다.

**[예제 데모 바로 보기](https://lsk961215.github.io/routing-engine/)** — 설치 없이 서울 경로 8개의 지도와 Dijkstra·A* 반복 측정 결과를 비교한다. 저장된 결과를 보여주는 정적 데모이며 실시간 경로 계산은 하지 않는다.

## 실행

### 로컬 개발

Java 21, Node.js/npm, Python 3가 필요하다. 최초 데이터 생성에는 osmium도 필요하다.
그래프가 없으면 [데이터 준비](backend/data/README.md)를 먼저 진행한다.
프로젝트 루트에서 실행한다. 최초 한 번 프론트 의존성을 설치한다.

```sh
npm ci --prefix frontend
./scripts/start.sh                  # 백엔드 :8080 + 프론트 :5173 백그라운드 실행
./scripts/status.sh                 # 실행 상태 확인
./scripts/stop.sh                   # 모두 종료
```

Windows에서는 루트의 `start.bat`을 더블클릭해 기동하고 `stop.bat`으로 종료한다. Windows의 Node.js/npm과 WSL Ubuntu의 Java 21·Python 3·curl·lsof가 필요하다. 프론트 의존성이 없으면 자동 설치하며, 백엔드 빌드 후 WSL IP를 확인해 프론트 프록시를 연결한다. 접속 주소는 `http://localhost:5173`이다.

이미 실행 중인 서버는 중복 실행하지 않는다. 로그·PID·로컬 설정은 `.runtime/`에 저장하며 두 배치 파일은 결과를 확인할 수 있도록 창을 유지한다. WSL 재시작 후에는 `start.bat`을 다시 실행해 변경된 IP를 반영한다.

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

### Docker Compose

Docker와 Docker Compose 2.20 이상이 필요하다. API는 Java 21 이미지에서 빌드하고, 프론트는 Node 24에서 빌드한 정적 파일을 Nginx가 제공한다.
그래프가 준비돼 있다면 호스트의 Java나 Node 설치는 필요하지 않다. 아래 명령은 **프로젝트 루트**에서 실행한다.

먼저 [데이터 준비](backend/data/README.md)에 따라 다음 파일을 준비한다. 데이터는 Git과 이미지에 포함되지 않는다.

- `backend/data/processed/seoul.rgraph`
- `backend/data/processed/seoul.rgraph.exclusions.geojson`

Compose는 두 파일을 API 컨테이너의 `/data`에 읽기 전용으로 연결한다. 파일이 없으면 기동에 실패하며 빈 디렉터리를 대신 만들지 않는다.
로컬 개발 서버나 기존 수동 실행 컨테이너가 8080·8082 포트를 사용 중이면 먼저 종료한다.
로컬 서버는 `./scripts/stop.sh`, 기존 수동 컨테이너는 `docker stop routing-frontend routing-api`로 종료할 수 있다.

빌드와 실행:

```sh
docker compose up --build -d --wait --wait-timeout 180
```

접속 주소는 **http://localhost:8082**다. API는 **http://localhost:8080**이며 두 포트 모두 로컬 호스트에만 공개한다.
배경 지도는 브라우저가 OpenFreeMap에서 직접 불러오므로 외부 인터넷 연결이 필요하다.

`compose.yaml`이 이미지 빌드·컨테이너·전용 네트워크를 함께 관리한다. 별도로 `docker network create`를 실행할 필요가 없다.
Nginx는 서비스 이름 `routing-api`로 API를 찾아 `/api/` 요청을 전달한다.
API의 `/actuator/health`가 정상 응답한 뒤 프론트를 시작하고, `--wait`는 두 서비스의 상태 확인이 통과할 때까지 기다린다.
이 대기 시간은 이미지 빌드 시간이 아닌 컨테이너 기동에 적용된다.

상태·로그와 연결 확인:

```sh
docker compose ps
docker compose logs --tail 100
docker compose logs -f routing-api routing-frontend
```

`logs -f`는 Ctrl+C로 나가도 서버가 계속 실행된다. 경로·비교 API 연결은 다음처럼 확인한다.

```sh
curl -fsS http://127.0.0.1:8080/actuator/health
curl -fsS -o /dev/null -w '경로 API HTTP %{http_code}\n' \
  'http://127.0.0.1:8082/api/route?startLon=126.97721885&startLat=37.5690142&endLon=127.0276&endLat=37.4981'
curl -fsS -o /dev/null -w '비교 API HTTP %{http_code}\n' \
  'http://127.0.0.1:8082/api/compare?startLon=126.97721885&startLat=37.5690142&endLon=127.0276&endLat=37.4981'
```

종료·재실행·소스 변경 반영:

```sh
docker compose stop                                      # 컨테이너를 남기고 종료
docker compose up -d --wait --wait-timeout 180             # 기존 이미지로 다시 실행
docker compose up --build -d --wait --wait-timeout 180     # 소스 수정 후 빌드·교체
docker compose down                                      # 컨테이너와 전용 네트워크 제거
```

`down`은 호스트의 그래프 파일과 빌드된 이미지를 삭제하지 않는다. 컨테이너는 `scripts/start.sh`, `stop.sh`, `status.sh`의 관리 대상이 아니다.
Compose는 기존 수동 컨테이너와 다른 이름을 사용하며 기존 `routing-net`도 관리하지 않는다.

전체 서비스를 `up --build`로 갱신하거나 `docker compose restart routing-api`로 API를 재시작할 때는
`depends_on.restart: true`로 프론트도 재시작해 Nginx가 API 주소를 다시 조회한다.
이 동작은 Compose를 통한 명시적 작업에 적용된다([공식 시작 순서 안내](https://docs.docker.com/compose/how-tos/startup-order/)).
`docker compose up ... routing-api`처럼 API만 지정해 교체하면 프론트는 갱신 대상에서 빠진다.
이때는 `docker compose restart routing-frontend`도 실행한다. 개별 컨테이너에 직접 `docker restart`를 실행하거나 `--no-deps`를 사용한 경우도 같다.

- `port is already allocated`: 로컬 서버 또는 기존 수동 컨테이너가 8080·8082 포트를 사용 중인지 확인한다.
- 데이터 마운트 오류: 위 두 파일의 존재 여부와 읽기 권한을 확인한다. 그래프 생성은 [데이터 준비](backend/data/README.md)를 따른다.
- API가 `unhealthy`이면 `docker compose logs --tail 100 routing-api`로 그래프 적재·기동 오류를 확인한다.
- 프로덕션 화면에서 지도가 준비되지 않는 경우: `frontend/src/main.ts`의 `?worker&url` import와 `setWorkerUrl` 설정을 확인한다. Worker 요청에 HTML이 반환되면 빌드 산출물이 누락된 것이다.
- 각 `.dockerignore`는 로컬 데이터·의존성·빌드 결과·환경 파일을 제외한다. 프론트 의존성은 이미지 안에서 `npm ci`로 설치한다.

### GitHub Pages 예제 데모

공개 주소: **https://lsk961215.github.io/routing-engine/**

간결한 지도 화면에서 예제 8개의 Dijkstra·A*·랜드마크 A*(ALT) 경로와 실제 탐색 영역 확산, 알고리즘별 표시 토글, 거리·시간 중앙값·확장 상태 수를 비교한다. 측정 조건은 접힌 설명에서, p95·메모리 할당량을 포함한 전체 측정값은 JSON 다운로드로 확인한다.
실제 엔진에서 추출한 결과를 정적 파일로 제공하므로 `/api/`나 Java 서버를 호출하지 않는다. 배경 지도는 OpenFreeMap에서 불러온다.
지도 서버 연결에 실패해도 예제 선택과 측정 결과는 볼 수 있다. 선택한 예제는 URL 해시로 공유할 수 있고, 전체 예제 JSON을 내려받을 수 있다.

**탐색 확산**은 한 지도에 Dijkstra를 파란 점, A*를 주황 점, ALT를 초록 점으로 겹쳐 표시한다. 첫 화면과 경로 선택 시 탐색을 처음부터 자동 재생한다.
**처음부터 재생**·일시정지·슬라이더·처음/끝 버튼으로 실제 확장 순서를 살펴본다. 알고리즘별 체크박스는 영역과 경로를 함께 켜고 끈다.
**탐색 영역 표시**를 끄면 최종 경로만 비교할 수 있다. 각 최종 경로는 해당 알고리즘의 확장이 끝날 때 나타난다.

세 알고리즘에 같은 누적 확장 횟수를 적용하고, 초반 탐색을 보기 쉽도록 재생 위치의 세제곱에 비례해 횟수를 늘린다.
재생 시간은 실제 실행 시간이 아니다. 약 120m 격자마다 처음 확장한 실제 노드 하나를 표시하며, 점 개수는 전체 확장 상태 수가 아니다. 점은 지도 축척에 따라 크기를 조절하며 세 알고리즘에 같은 반경을 사용한다. 진한 점은 최근 확장한 위치, 옅은 점은 누적 탐색이다. 도로 연결과 통행 규칙을 따르므로 확산이 원형이거나 연속된 면일 필요는 없다.
재생 기록은 성능 측정과 별도로 수집한다. Dijkstra/A*와 Dijkstra/ALT는 같은 그래프·질의·설정·환경에서 별도 실행한 반복 측정이며, 화면의 Dijkstra 수치는 Dijkstra/A* 실행에서 가져온다. 각 실행의 측정일·설정·환경과 ALT 전처리 개수·시간·거리 테이블 크기를 내려받는 JSON에 구분해 기록한다. ALT 카드의 탐색 시간은 전처리를 제외하며, 서로 다른 실행의 시간 차이를 알고리즘 효과만으로 단정하지 않는다.
기록은 예제 선택 시 해당 JSON만 불러온다. 로딩 실패 시 재시도할 수 있고, 예제 변경 전의 늦은 응답은 무시한다.

```sh
npm ci --prefix frontend
npm run build:demo --prefix frontend
npm run preview:demo --prefix frontend
# http://localhost:4173/routing-engine/
```

데모는 `frontend/demo/index.html`·`frontend/src/demo.tsx`·`demo.css`를 진입점으로 사용한다.
데모 UI는 React 19와 [Astryx](https://github.com/meta/astryx)의 Neutral 테마를 사용한다. 경로 선택은 Selector, 표시 토글은 Switch, 재생 조작은 IconButton·Slider 컴포넌트다. 처음·재생/일시정지·끝 이동을 아이콘 그룹으로 묶고, 버튼의 툴팁과 접근 가능한 이름을 제공한다. 데스크톱에서는 지도 옆에 알고리즘별 시간·거리·확장 상태 카드를, 태블릿에서는 지도 아래 세 열, 좁은 모바일에서는 한 열로 배치한다. 터치 화면의 경로 메뉴는 하단 시트로 열린다. 측정 조건은 접어 두었다.
`demo-map.ts`는 지도 수명과 경로 표시를, `demo-exploration.ts`는 요청 취소·확산 재생과 상태 통지를 담당한다. React가 컨트롤 상태를 관리하며 재생 코드가 화면 DOM을 직접 수정하지 않는다.
Astryx API 문서는 `npm run astryx --prefix frontend -- component Selector Slider Button Switch`로 확인한다. 패키지가 제공하는 CSS와 테마를 사용하므로 별도 StyleX 빌드 플러그인은 필요하지 않다.
배경 지도는 OpenFreeMap Positron이다. 화면에 사용하는 IBM Plex Sans KR 글꼴은 `frontend/src/fonts/`에서 직접 제공하며, OFL 라이선스는 `frontend/demo/public/fonts/`에 포함한다.
`--mode demo`일 때만 `/routing-engine/` 경로와 `frontend/dist-demo/` 출력을 사용하며, 기존 `npm run build`와 Docker 프론트는 실시간 탐색 화면을 유지한다.
GitHub 저장소 Settings → Pages의 Source는 **GitHub Actions**로 설정한다.
`.github/workflows/pages.yml`이 main의 프론트 변경 또는 수동 실행 시 빌드하고 Pages에 배포한다.
새 도로 데이터 생성과 벤치마크는 배포 과정에서 실행하지 않는다.

데이터 갱신은 서울 API가 같은 그래프로 실행 중인 로컬 환경에서 수행한다.

```sh
./scripts/benchmark.sh -PbenchmarkReport=../.runtime/benchmarks/my-demo-run
./scripts/benchmark.sh -PbenchmarkAlgorithm=alt -PbenchmarkReport=../.runtime/benchmarks/my-demo-alt-run
python3 scripts/export-demo.py .runtime/benchmarks/my-demo-run/report.json \
  --alt-report .runtime/benchmarks/my-demo-alt-run/report.json
./backend/gradlew -p backend :routing-api:exportDemoTraces
npm run build:demo --prefix frontend
```

`export-demo.py`는 두 보고서의 그래프·고정 질의 해시, 질의 집합·설정·환경·기준 커밋을 검사한다. `/api/compare`의 기본 비교와 `algorithm=alt` 비교에서 거리·확장 상태·연결 좌표가 벤치마크와 일치하는지 확인한 후 세 알고리즘 데이터를 한 번에 교체한다. 측정 횟수를 바꿀 때는 두 보고서에 같은 설정을 사용한다.
출력 `frontend/src/demo-data.json`은 버전 관리한다. 경로 형상은 API에서, 시간·메모리 통계는 독립 JVM 반복 측정 보고서에서 가져온다.
출처에 측정일·엔진 커밋·JVM 환경·그래프/질의 해시를 포함한다. API 주소를 바꾸려면 `--api-url http://...`를 지정한다.

`exportDemoTraces`는 `CoordinateRouter.trace`로 각 확장 시점의 노드 좌표와 순서를 기록하고,
모든 예제의 경로 형상·거리·확장 상태 수가 `demo-data.json`과 일치하는지 확인한 뒤 `frontend/demo/public/traces/`에 저장한다.
그래프·질의 해시, 데모 JSON 해시, 기록 시각, 좌표/순서 필드와 격자 크기도 포함한다. 샘플링은 오프라인 내보내기에서 수행한다.
데모 데이터·탐색 기록은 v2이며 예제마다 세 알고리즘을 포함한다. 브라우저는 예전 두 알고리즘 기록을 감지하면 오류와 재시도를 제공한다. 이 파일들도 버전 관리하며, 데모 전용 정적 디렉터리를 사용하므로 실시간 앱의 배포 파일에는 포함되지 않는다.

정적 빌드 미리보기 실행 후 브라우저 검증:

```sh
python3 scripts/test-export-demo.py
node scripts/browser-demo-check.cjs
# 공개 배포 검증
DEMO_URL=https://lsk961215.github.io/routing-engine/ node scripts/browser-demo-check.cjs
```

Astryx 경로 메뉴·키보드 슬라이더·터치 하단 시트·예제 전환·실제 렌더링된 세 알고리즘 확산 색상·ALT 완료 전 초록 점 표시·이전 v1 기록 거부·재생/일시정지/구간 이동·표시 토글·늦은 응답 무시·기록 로딩 재시도·해시 링크·JSON 내려받기·모바일·지도 연결 실패·API 요청 없음·Worker 응답을 확인한다.
스크린샷은 `.runtime/demo-check/`에 저장한다. Playwright 준비는 아래 [검증](#검증)을 따른다.

### 화면 사용

로컬 개발은 `http://localhost:5173`, Docker 실행은 `http://localhost:8082`에 접속한다.
사이드 패널에서 출발지·도착지를 선택하고 지도에 지정한 뒤 **경로 탐색**을 누른다.
**함께 비교 → 경로 비교**를 누르면 같은 좌표 연결을 공유하는 두 알고리즘의 거리·탐색/복원 시간·확장 상태 수·메모리 할당량을 나란히 표시한다.
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
`allocatedBytes`는 탐색·경로 복원 동안 현재 스레드에 할당한 힙 메모리의 추정 누계다. 좌표 연결·그래프/인덱스 적재·응답 DTO/JSON 변환은 제외한다.
[JDK ThreadMXBean](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.management/com/sun/management/ThreadMXBean.html#getCurrentThreadAllocatedBytes())의 카운터 차이를 사용하므로 다른 요청 스레드의 할당과 섞이지 않는다.
GC로 회수된 객체도 할당 누계에 포함하며 현재·최대 점유량이나 프로세스 메모리 사용량을 뜻하지 않는다. 지원 JVM에서는 카운터를 초기화 시 활성화하며, 미지원·비활성·권한 제한·가상 스레드는 `null`(화면에는 **측정 불가**)로 표시한다. 실제 0바이트와 구분한다.
비교 API는 좌표를 한 번 연결한 뒤 Dijkstra → A* 순서로 실행하며 각 탐색 상태는 독립적이다.
공통 좌표 연결 시간은 한 번만 표시한다. 워밍업·반복 측정을 적용한 벤치마크는 아니므로 단일 요청의 시간만으로 성능을 단정하지 않는다.

지도에서 서울 데이터 추출 영역(보라색 점선)과 바깥쪽 음영을 켜고 끌 수 있다.
**영역 전체 보기**로 경계를 확인한다. 행정 경계나 API의 강제 차단 경계는 아니며, 경계 도로 일부는 바깥까지 포함한다.

그래프 파일은 버전·CRC를 검증한다. v1은 노드 회전 제한, v2는 via-way,
v3는 조건부 방향을 추가한다. 서울 시험 그래프는 v2이며 파일 상한은 64MiB다.

## API와 한계

- `GET /api/route?startLon=...&startLat=...&endLon=...&endLat=...&algorithm=dijkstra`
  — `algorithm=dijkstra|astar|alt`(기본 Dijkstra). 경로·거리·연결 좌표·`metrics` 반환. `/route`도 지원한다.
- `GET /api/compare?startLon=...&startLat=...&endLon=...&endLat=...`
  — 공통 `waypoints`·`snapMillis`와 `results`(Dijkstra, A* 순서)를 반환한다. `&algorithm=alt`를 추가하면 Dijkstra와 ALT를 비교한다. 비교 알고리즘은 `astar|alt`만 허용한다.
  각 결과에는 `algorithm`, `code=Ok|NoRoute`, `routes`, `metrics.searchMillis`, `metrics.expandedStates`, `metrics.allocatedBytes`가 있다. 개별 경로 API도 같은 할당량 지표를 반환한다.
  경로가 없어도 HTTP 200으로 `NoRoute`·빈 `routes`·측정값을 반환한다. 좌표 오류·영역 밖·그래프 미설정은 400·422·503이다.
- `GET /api/excluded-roads?west=...&south=...&east=...&north=...`
  — 현재 영역과 경계 상자가 겹치는 제외 도로·사유 반환.
- 경로 오류: 400 좌표·알고리즘 오류, 404 경로 없음, 422 도로에서 50m 초과, 503 그래프 미설정.

현재 거리 기준 탐색이며 예상 시간은 제공하지 않는다. 명시적 자동차 통행 금지는 제외하고,
미해석 규칙은 기록만 남긴다. 도로를 연쇄 제외하지 않으며, 모든 알고리즘은 같은 그래프·규칙을 사용한다.
조건부 일방통행의 제작·시각 고정 탐색은 코어에 구현되어 있지만 HTTP API에는 연결되지 않았다.
지원 시간대는 Asia/Seoul이며 주행 중 시간 변화·조건부 회전 제한은 지원하지 않는다.

## 랜드마크 A* (ALT)

`algorithm=alt`는 [ALT의 방향 그래프 하한](https://www.microsoft.com/en-us/research/publication/computing-the-shortest-path-a-search-meets-graph-theory/)을 기존 A*에 결합한다. 랜드마크 L, 현재 노드 v, 목적지 t에 대해 `d(L,t) - d(L,v)`와 `d(v,L) - d(t,L)` 중 유효한 양수 하한을 사용한다. 기존 구면거리 하한과 최댓값을 취한다.

- 기본 랜드마크는 최대 8개다. 가장 큰 강연결 성분(서로 왕복 도달 가능한 도로 집합)에서 차수가 가장 높은 노드부터 시작하고, 선택된 랜드마크와의 방향별 거리 중 작은 값에 대해 가장 먼 노드를 차례로 고른다. 동점은 노드 인덱스로 결정한다. 비자명한 강연결 성분이 없는 그래프는 가장 큰 약연결 성분을 사용한다. 랜드마크와 유효한 거리 관계가 없는 노드는 구면거리 하한으로 탐색한다.
- 전처리는 원래 간선의 방향·비용을 유지하되 회전 제한을 제거한 그래프에서 정방향·역방향 최단 거리를 계산한다. 실제 질의는 기존 `(진입 간선, 회전 이력)` 상태 탐색을 사용해 노드·via-way 회전 제한을 지킨다.
- 도로 중간 목적지는 진입 간선 시작점까지의 하한에 해당 간선의 부분 비용을 더하고, 후보 진입 간선별 최솟값을 사용한다. 도달 불가능한 랜드마크 거리는 하한 계산에서 제외하며 부동소수점 차감에는 작은 하향 여유를 둔다.
- 인덱스는 해당 불변 그래프를 소유한 `CoordinateRouter`에 귀속된다. 첫 ALT 요청에서 한 번 만들고 동시 요청들이 공유한다. 서버 재시작·새 라우터 생성 시 다시 계산하며 디스크에는 저장하지 않는다.
- 첫 ALT 요청은 전처리를 기다릴 수 있다. `searchMillis`·`allocatedBytes`·`snapMillis`에는 전처리를 포함하지 않는다. 명시적 사전 준비는 `router.prepareLandmarks()`이며 개수·전처리 시간·거리 테이블 바이트를 반환한다. 테이블 용량은 `2 × 랜드마크 수 × 노드 수 × 8`바이트로, 객체 부가 비용과 전처리 임시 메모리는 제외한다.
- Java에서 `new CoordinateRouter(graph, landmarkCount)`로 1–32개를 지정할 수 있다. 실제 개수는 후보 노드 수 이하이며 기본 API는 8개를 사용한다. 전처리는 워밍업 전에 수행해 벤치마크 질의 구간과 분리한다.

```sh
curl 'http://127.0.0.1:8080/api/route?startLon=126.97721885&startLat=37.5690142&endLon=127.0276&endLat=37.4981&algorithm=alt'
curl 'http://127.0.0.1:8080/api/compare?startLon=126.97721885&startLat=37.5690142&endLon=127.0276&endLat=37.4981&algorithm=alt'
```

## 반복 성능 측정

```sh
./scripts/benchmark.sh
# Dijkstra와 랜드마크 A* 비교
./scripts/benchmark.sh -PbenchmarkAlgorithm=alt
```

서버 실행 없이 전용 JVM에서 서울 고정 질의 8개를 측정한다. 기본 설정은 **독립 JVM 3개 × 질의별 워밍업 4회 + 측정 20회**다.
JVM은 순차 실행하며 각 JVM의 힙은 `-Xms1g -Xmx2g`, GC는 G1이다. 실행 중 다른 부하 작업을 피한다.
질의 좌표는 `backend/data/seoul-benchmark-queries.json`에 고정한다. 질의 순서는 시드로 섞고, 각 질의에서 먼저 실행하는 알고리즘을 매 라운드 교대한다.
짝수 측정 횟수만 허용하므로 알고리즘마다 먼저/나중 실행한 표본 수가 같다. 두 알고리즘은 매번 같은 좌표 연결 결과를 공유한다.

완료 시 출력하는 `.runtime/benchmarks/<실행 ID>/report.html`을 브라우저에서 열면 다음을 확인할 수 있다.

- 질의·알고리즘별 표본 수, 중앙값(p50), p95, 최소·최대, 평균·표준편차.
- JVM별 또는 먼저/나중 실행한 표본만 선택한 통계와 그래프.
- **비교 지표**에서 시간(ms)·메모리 할당량(MiB)을 선택한다. 할당량 분포는 측정 가능한 표본만 사용하고 유효/전체 표본 수를 표시한다. 전부 측정 불가이면 통계도 `null`이다. JSON은 바이트 단위를 유지한다.
- `report.json`: 모든 원시 표본, 질의 좌표, 설정, 그래프/질의 SHA-256, JVM·OS·Git 정보, GC 횟수·시간.
- `fork-1.json` 등: 각 독립 JVM의 측정 결과. 기존 출력 디렉터리를 지정하면 덮어쓰지 않고 실패한다.

`searchMillis`는 탐색·경로 복원만 포함한다. 그래프/인덱스 초기화·랜드마크 전처리·좌표 연결·HTTP·JSON·워밍업은 제외한다. ALT 보고서는 각 JVM의 `landmarks`에 개수·전처리 시간·거리 테이블 바이트를 별도 기록하고 HTML 측정 조건에 표시한다. 기본 Dijkstra/A* 보고서는 `landmarks=null`이다.
거리·경로 유무·연결 좌표가 두 알고리즘에서 일치하는지 매 쌍 검증하며 불일치하면 중단한다.
자연 발생한 GC와 이상값은 제거하지 않는다. 중앙값은 짝수 표본의 가운데 두 값 평균, p95는 nearest-rank 방식이다.
서로 다른 질의는 하나의 분포로 합치지 않는다. JVM별 편차를 함께 확인하며, 고정 워밍업만으로 JIT 안정화나 통계적 유의성을 보장하지는 않는다.
보고서 형식은 v2이며 원시 표본의 `allocatedBytes`, 요약의 `allocatedBytes`·`firstAllocatedBytes`·`secondAllocatedBytes` 분포를 추가한다. 시간과 할당량의 측정 구간은 동일하며 카운터 조회는 시간 측정 바깥에서 수행한다. 계측이 추가된 실행과 이전 실행의 시간 차이를 알고리즘 개선으로 해석하지 않는다.

설정 변경 예시(경로는 `backend/` 기준):

```sh
./scripts/benchmark.sh -PwarmupRounds=6 -PmeasuredRounds=30 -Pforks=3 -Pseed=20260926
./scripts/benchmark.sh -Pqueries=data/seoul-benchmark-queries.json -PbenchmarkReport=../.runtime/benchmarks/my-run
```

기존 `benchmarkRouting`은 이전/현재 구현을 비교하는 진단 도구다. Dijkstra·A* 또는 Dijkstra·ALT 반복 비교에는 위 스크립트를 사용한다. 공개 데모 데이터 갱신에는 기본 `astar` 보고서와 같은 설정의 `alt` 보고서를 함께 사용한다.

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
