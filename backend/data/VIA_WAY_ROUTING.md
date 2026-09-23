# via-way 회전 제한의 탐색 적용

## 구현 범위

정상 from → via Way(하나 이상) → to 관계를 방향성 간선의 연속 경로로 변환한다.
한 via Way가 여러 형상 구간으로 나뉘어도 전체 경유 이력을 유지한다.

- `no_*`: 관계가 지정하는 연속 경로를 완성하는 마지막 진출을 금지한다. 중간에 다른 길로 나가는 것은 허용한다.
- `only_*`: 지정된 진입 이후 관계의 경유 경로를 따라가도록 한다. 중간 출구와 다른 진출을 금지한다.
- 다른 도로에서 via에 진입하거나 via 내부에서 새로 출발한 요청에 from 이력을 만들어 붙이지 않는다.
- 목적지가 경유 도로 중간이면 그 지점에서 경로를 종료할 수 있다.
- 도로 중간 좌표에서 출발·도착할 때도 부분 거리와 이력을 함께 계산한다.

의미는 [OSM restriction 문서](https://wiki.openstreetmap.org/wiki/Relation:restriction)를 참고했다.

## 구조

`ViaWayCompiler`가 관계 멤버 순서를 지키는 방향성 간선 경로들을 열거한다.
한 관계에 여러 정상 경로가 있으면 대안으로 묶는다. 기존 노드 경유 금지 회전도 동시에 적용한다.

`TurnSequences`는 경로 접두부와 실패 연결을 가진 읽기 전용 상태 머신을 그래프 생성·로딩 시 만든다.
겹치는 제한도 추적하며, 진행 상태에 맞지 않는 전이를 거부한다.
`only_*`의 대안은 같은 관계 안에서 허용 경로로 묶고, 독립 관계의 요구사항은 함께 적용한다.

via-way 제한이 있는 그래프는 `HistorySearch`로 **(직전 간선, 제한 진행 상태)**를 탐색한다.
같은 직전 간선이라도 다른 경로로 들어왔으면 다른 상태로 보관한다.
탐색 맵과 큐는 요청마다 분리하며 그래프를 복사하지 않는다.
via-way 제한이 없는 기존 그래프는 앞서 최적화한 배열 기반 탐색을 계속 사용한다.

## 파일 형식

- v1 파일을 계속 읽으며, via-way 제한이 없는 그래프는 v1로 저장한다.
- via-way 제한이 있으면 v2로 저장한다.
- v2는 기존 배열 뒤, CRC 앞에 관계 수와 각 관계의 only 여부·대안 수·간선 순서 목록을 추가한다.
- 로딩 시 크기·개수·간선 인덱스·연속 연결·CRC를 확인한 뒤 상태 머신을 재구성한다.
- `arrayPayloadBytes`는 기존 CSR 배열 본체만 센다. 경로 목록·상태 머신·HashMap 비용은 별도다.

## 검증 결과

백엔드 테스트 **57개 통과**.

- 동일 via 간선에 서로 다른 이력으로 도착할 때 금지 여부 구분 및 합법적인 우회
- only 제한의 초기·중간 출구 차단, 다른 진입 도로에서는 허용
- 복수 via Way와 한 Way의 여러 형상 간선
- 시작·도착이 구간 중간인 요청 및 via 내부에서 출발한 요청
- 겹치는 제한·복수 대안 및 독립적인 이력 검사 방식과의 비교
- 공유 상태 머신에서 동시 요청
- 합성 PBF → 그래프 생성 → v2 저장·재적재 → 탐색
- 손상 파일·잘못된 간선 순서 거부

실제 OSM 관계 **8107280**와 참조 엔티티만 전국 원본에서 추출해 추가 검증했다.
생성 그래프는 노드 4개, 간선 3개, via-way 제한 1개다.

| 요청 | CLI 결과 | 좌표 API 결과 |
| --- | --- | --- |
| from에서 들어와 금지된 to로 진출 | NO_ROUTE | HTTP 404 |
| via 시작점에서 새로 출발해 to로 진출 | 약 74.529968m | HTTP 200, 동일 거리 |

이 파일은 특정 관계 검증용 부분집합이다. 주변 도로와 다른 관계까지 포함한 운행용 지역 그래프가 아니다.
검증용 API 서버는 종료했다.

## 재현

프로젝트 루트에서 출력 파일이 없는 상태로 실행한다.

```sh
osmium getid backend/data/raw/south-korea-260919.osm.pbf r8107280 -r \
  -o backend/data/processed/via-way-example.osm.pbf
./backend/gradlew -p backend :routing-core:buildGraph \
  -Ppbf=data/processed/via-way-example.osm.pbf \
  -Pgraph=data/processed/via-way-example.rgraph
./backend/gradlew -p backend :routing-core:routeGraph \
  -Pgraph=data/processed/via-way-example.rgraph -Pstart=0 -Pend=3
./backend/gradlew -p backend :routing-core:routeGraph \
  -Pgraph=data/processed/via-way-example.rgraph -Pstart=2 -Pend=3
```

## 남은 제한

- 앞서 검사한 147개는 연결·방향 검사 통과 수이며, 이번에 147개 모두를 한 그래프로 생성했다는 뜻은 아니다.
- 분석기의 `VIA_WAY_DEFERRED`는 기존 분류 이름으로 남아 있다. GraphBuilder는 이 표시만으로 입력을 거부하지 않고 새 컴파일러로 검증·변환한다. 다른 검사 사유가 있으면 계속 거부한다.
- 조건부 제한, 누락 참조, 방향 충돌과 자동차 프로필의 다른 미지원 항목은 계속 차단한다.
- 반복되는 via Way, from/to와 같은 via Way는 현재 모호한 입력으로 거부한다.
- 관계당 via Way 32개, 경로 길이 128개, 대안 256개, 열거 방문 10만 회 등 한도를 둔다. 한도를 넘으면 일부만 적용하지 않고 생성을 실패시킨다.
- 전체 관계 수 1만 개, 간선 시퀀스 합 10만 개를 넘는 파일도 거부한다.
- 경로 이력 상태가 추가되므로 이전 무이력 그래프의 성능 수치를 그대로 적용할 수 없다. 확대된 실제 입력에서 상태 수·메모리·처리 시간 측정이 남아 있다.
