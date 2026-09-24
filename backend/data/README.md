# 데이터 준비와 제외 정책

## 데이터 제작

현재 스냅샷은 `south-korea-260919.osm.pbf`다. 원본 공급처는 Geofabrik이다.
해당 원본을 `backend/data/raw/`에 준비한다. 다른 스냅샷에서는 아래 보충 ID를 재검토해야 한다.
osmium 설치: macOS `brew install osmium-tool`, Ubuntu/WSL `sudo apt install osmium-tool`.

프로젝트 루트에서 실행한다. 출력 파일이 이미 있으면 새 경로를 사용한다.

```sh
mkdir -p backend/data/raw backend/data/processed
osmium extract -b 126.70,37.35,127.25,37.80 \
  backend/data/raw/south-korea-260919.osm.pbf \
  -o backend/data/processed/seoul-routing.osm.pbf
osmium getid backend/data/raw/south-korea-260919.osm.pbf \
  w468202418 w468202416 w1460674135 --add-referenced \
  -o backend/data/processed/seoul-restriction-supplement.osm.pbf
osmium merge backend/data/processed/seoul-routing.osm.pbf \
  backend/data/processed/seoul-restriction-supplement.osm.pbf \
  -o backend/data/processed/seoul-routing-complete.osm.pbf
scripts/build-seoul.sh
```

서울 행정 경계보다 넓게 추출한다. `complete`는 확인된 누락 Way 3개를 보충했다는 뜻이며 오류가 없다는 뜻은 아니다.
제작 스크립트는 `processed/seoul.rgraph`와 옆의 `.exclusions.tsv`, `.exclusions.geojson`을 만든다.
원본·대용량 산출물은 Git에서 제외한다. 테스트용 합성 PBF는 저장소에 포함한다.

기존 그래프에 지도 표시 데이터만 추가하려면:

```sh
python3 scripts/export-excluded-roads.py \
  backend/data/processed/seoul-routing-complete.osm.pbf \
  backend/data/seoul-exclusions.tsv \
  backend/data/processed/seoul.rgraph.exclusions.geojson
```

## 현재 그래프

| 항목 | 값 |
| --- | ---: |
| 비교용 도로 | 173,755 |
| 복원한 기존 제외 도로 | 3,857 / 3,857 |
| 명시적 접근 금지 도로 | 889 |
| 노드 / 방향성 간선 | 831,368 / 1,664,236 |
| 금지 간선 전이(장벽 포함) / via-way 규칙 | 5,832 / 143 |
| 파일 크기 | 약 54.0MiB |

파일 크기는 공간 인덱스·요청별 탐색 상태·JVM을 포함한 서버 메모리 사용량과 다르다.

## 제외 정책

`SeoulPreparation`은 알고리즘 비교용 정적 정책을 적용한다.

- 자동차 대상 도로에서 `motorcar → motor_vehicle → vehicle → access` 우선순위로 판정해 명시적 `no`만 제외한다.
- `private`, `destination`, 미해석 접근 조건은 허용한다. `road`·`track`도 포함하고 보행자 전용 등 자동차 비대상 도로는 제외한다.
- 알려진 일방통행 방향은 유지한다. 조건부 방향은 적용하지 않고 정적 기본 방향을 쓴다. 방향을 판단할 수 없으면 기본 도로 규칙을 쓴다.
- 정상 회전 제한은 유지한다. 누락·모호한 관계는 사유를 기록하고 해당 규칙만 생략한다. 관련 도로를 연쇄 제외하지 않는다.
- gate 태그만으로 차단하지 않는다. 명시적 자동차 금지나 bollard·wall 등 확실한 장벽은 해당 노드의 간선 간 통과만 막는다. 접근 도로 형상은 유지한다.

서울 제작 스크립트는 `buildGraph -Pcomparison=true`를 사용한다. 기본 GraphBuilder의 엄격 모드와 조건부 코어는 유지한다.
지도에는 명시적 접근 금지 도로 889개를 빨강으로 표시한다. 비대상 도로·차단 노드 전체를 표시하는 것은 아니다.
감사 TSV의 `relation`은 미적용 관계, `ignored_way_rule`은 미적용 속성, `blocked_node`는 통과 차단 지점이다.
기존 파일명 `.exclusions.tsv`는 유지하지만 이제 도로 제외와 규칙 미적용 기록을 함께 담는다.

## 검증 자료

현재 제외 목록은 [seoul-exclusions.tsv](seoul-exclusions.tsv), 대표 경로 좌표·결과는
[seoul-smoke-results.json](seoul-smoke-results.json)에 있다. 나머지 JSON·TSV·TXT는 기존 조사·측정 자료다.
상세 처리 기준은 소스와 테스트를 기준으로 확인한다. 실행·검증 명령은 [프로젝트 안내](../../README.md)를 참고한다.
