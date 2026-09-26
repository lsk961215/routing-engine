# 진행 상태 · 세션 인계

기준: 2026-09-27. 새 세션은 이 문서 → [실행·구조](README.md) → 필요한 소스 순서로 확인한다.

## 프로젝트 방향과 사용자 선호

- **목표는 알고리즘별 경로·성능 비교**다. 실제 서비스용 내비게이션이 아니다.
- 같은 그래프·좌표·비용·통행 규칙에서 비교한다. 최단 비용이 같으면 경로 모양은 달라도 된다.
- 데이터 정합성 조사에 치중하지 않는다. 불명확한 제한 때문에 도로를 연쇄 제외하지 않는다.
- 문서는 현재 3개만 유지한다. 별도 작업 기록 MD를 늘리지 말고 기존 문서를 갱신한다.
- 화면은 간결하게 유지한다. 예제 버튼과 개발 정책·지점 선택 안내 문구는 사용자 요청으로 제거했다.
- 작업은 기능별로 커밋한다. 사용자는 `chore`보다 내용을 설명하는 커밋 메시지를 선호한다.

## 현재 완료

- 서울 비교 그래프: 도로 173,755개, 노드 831,368개, 방향성 간선 1,664,236개.
- 기존 과잉 제외 도로 3,857개 복원. 명시적 자동차 접근 금지 889개를 제외 지도에 표시.
- 알려진 일방통행·정상 회전 제한 유지. 확실한 장벽은 해당 지점 통과만 차단한다.
- Dijkstra·A* 선택 API와 화면 구현. 좌표 연결 시간·탐색/복원 시간·확장 상태 수 표시.
- 동시 비교 API·화면: `/api/compare`에서 좌표를 한 번 연결하고 Dijkstra → A* 순차 탐색. 결과 표와 파란 실선·주황 점선, 경로별 표시 토글 제공.
- 비교 결과는 알고리즘별 `Ok`/`NoRoute`와 측정값을 유지한다. 요청 취소·모드 변경·초기화 후 늦은 응답은 무시한다.
- 실행·종료·상태 스크립트: `scripts/start.sh`, `stop.sh`, `status.sh`. `backend`/`frontend` 개별 대상 지원. 서버별 OS 잠금으로 동시 명령 충돌을 막으며 로그·PID는 `.runtime/`에 저장.
- 공정한 반복 측정: `scripts/benchmark.sh`로 고정 서울 질의 8개를 독립 JVM 3개에서 워밍업 4회·측정 20회. 질의 순서는 시드로 섞고 알고리즘 선행 순서는 질의별로 교대한다.
- 벤치마크는 질의별 분포와 실행 순서별/JVM별 통계를 HTML·JSON에 기록한다. 그래프·질의 해시, 환경, 원시 표본, GC 정보 포함. 경로 결과 불일치 시 중단한다.
- 사이드 패널: 지점별 좌표·재선택·교환·초기화·명시적 탐색 버튼.
- 알고리즘은 Dijkstra·A*·함께 비교 선택 버튼이다. 지도를 클릭한다고 탐색하거나 기존 두 지점을 초기화하지 않는다.
- 패널 경계 중앙의 SVG 화살표로 접기/펼치기. 모바일은 패널 아래쪽 경계에 배치한다.
- 지도 옵션: 데이터 영역과 제외 도로 두 행, 전체 보기 버튼. 영역은 추출 기준이며 강제 통행 경계가 아니다.
- 제목 `Routing Engine`, 경로 모양 SVG 파비콘 적용.
- 조건부 일방통행 코어는 구현했지만 HTTP에는 미연결. 서버는 조건부 v3 그래프를 거부한다.

## 다음 작업 — 미착수

1. **측정 확장**: 요청별 메모리 할당량 등 비교 지표 추가.

OSM 오류 조사·정밀 통행 규칙 복원, 시간 조건 API, 시간 비용 도입은 후순위다.

## 이어서 수정할 핵심 코드

- `backend/routing-core/src/main/java/com/lsk/routing/core/graph/CoordinateRouter.java`: 좌표 연결·공유 공간 인덱스. 개별 API는 `search(..., RoutingAlgorithm)`, 비교 API는 `compare(...)`를 사용한다.
- 같은 디렉터리의 `HistorySearch.java`: `(진입 간선, 회전 이력)` 상태 탐색. Dijkstra와 A*가 전이 로직을 공유한다.
- A* 하한은 간선 비용에 맞춘 구면거리다. 도로 중간 목적지의 부분 비용을 포함하므로 단순 직선거리로 교체하지 않는다.
- 기존 `route(...)`와 노드 기반 `DijkstraRouter`도 남아 있다. API 선택 기능과 혼동하지 않는다.
- `backend/routing-api/src/main/java/com/lsk/routing/api/service/RoutingService.java`, `dto/RouteResponse.java`, `dto/ComparisonResponse.java`: 알고리즘 선택·비교 응답 지표.
- `frontend/src/main.ts`: 패널·지점 상태·탐색 요청. 변경/초기화 시 이전 요청을 취소하고 늦은 응답을 무시한다.
- `frontend/src/style.css`, `coverage.ts`, `excluded-roads.ts`: 스타일·영역 표시·제외 도로 조회.
- `AlgorithmComparisonTest.java`: 임의 비용·회전 제한·부분 간선·동시 요청의 비용 동등성 검증.
- `backend/routing-api/src/test/java/com/lsk/routing/api/benchmark/AlgorithmBenchmark.java`: 서버를 띄우지 않는 반복 측정 실행기. `CoordinateRouter.compare(..., first)`로 실행 순서를 교대한다. 보고서 템플릿은 같은 모듈의 `src/test/resources/benchmark-report.html`.

현재 `searchMillis`는 탐색과 경로 복원을 포함하며 좌표 연결·JSON 변환·통신 시간은 제외한다.
`expandedStates`는 실제 상태 확장 횟수다. 단일 요청 수치를 공정한 벤치마크 결과로 간주하지 않는다.

## 실행·검증·데이터

- 기본 포트는 백엔드 8080, Vite 5173이다. 새 세션에서는 `./scripts/status.sh`로 실제 실행 상태를 확인한다.
- 실행·종료: 루트에서 `./scripts/start.sh`, `./scripts/stop.sh`. 상태는 `./scripts/status.sh`.
- 로컬 그래프: `backend/data/processed/seoul.rgraph`, 표시 데이터: 같은 경로의 `.exclusions.geojson`.
  이 파일과 원본 PBF는 Git 제외 대상이다. 다른 환경에서는 [데이터 준비](backend/data/README.md)를 따른다.
- 백엔드 112개 테스트·프론트 빌드·서버 잠금 및 실패 후 해제 검증 통과. 비교 API의 부분 간선·회전 제한·동시 요청·HTTP 응답 형식과 벤치마크 통계·워밍업 제외·실행 순서 균형·독립 JVM 보고서 생성 검증 포함.
- 기본 벤치마크를 실제 서울 그래프로 실행 완료(측정 표본 960개, 약 4분 33초). 로컬 결과: `.runtime/benchmarks/seoul-20260926/report.html` 및 `report.json`. Git 제외 대상이다. 원시 표본 통계 재검산과 보고서 JVM/순서 필터·모바일 검증 통과.
- 서울 대표 경로 8개에서 비교/개별 API 경로·좌표·확장 상태 일치 확인. 비교 화면의 경로 토글·모바일·경로 없음·서비스 오류·취소·늦은 응답 무시를 브라우저로 검증했다.
- 최신 패널 접기/펼치기·모바일·지점 보존도 브라우저 검증 완료. 검증 명령은 [README](README.md#검증) 참고.
- 브라우저 스크립트는 루트에서 `npm ci`와 `npm run browsers:install`로 준비한다. 지도 검증에는 실행 중인 서버가 필요하다. 앱에 전역 테스트 코드를 넣지 않고 테스트가 지도 참조를 주입한다.
- 프로젝트 내부 Playwright로 `npm run test:browser`의 5종 검증과 벤치마크 보고서 검증을 통과했다.
- 기존 JSON·TSV·TXT에는 이전 정책의 조사 결과도 섞여 있다. 최신 정책은 데이터 README와 현재 코드가 기준이다.

## Git 상태

새 세션에서는 `git status`, `git log -6 --oneline`, `git branch -vv`로 작업 폴더와 커밋·추적 브랜치 상태를 확인한다.
생성 보고서·PID·로그와 로컬 그래프는 Git 제외 대상이며, 테스트·실행 코드와 고정 질의 파일만 버전 관리한다.
