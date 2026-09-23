# 초기 자동차 프로필 및 회전 제한 검증

2026-09-23 구현. 일반 승용차의 **정적 통행 후보 분석**이다.
아직 그래프를 생성하지 않으며, 여기서 수락된 도로가 실제로 모든 통행 조건을 충족한다고 보장하지 않는다.

## 도로 판정

`CarProfile`은 `ACCEPTED`, `EXCLUDED`, `DEFERRED`와 방향·사유를 반환한다.

- 범위: motorway/trunk/primary/secondary/tertiary와 각 `_link`, unclassified, residential, living_street, service.
- 그 외 도로 유형은 이번 프로필의 범위 밖으로 제외한다. 예를 들어 motorcar=yes인 footway도 아직 지원하지 않는다.
- 노드가 2개 미만이거나 area=yes인 Way는 제외한다.
- 접근 태그 우선순위: motorcar → motor_vehicle → vehicle → access. 더 구체적인 값이 일반 값을 덮어쓴다.
- yes/permissive/designated는 허용 후보로 처리한다. 태그가 없으면 범위 안 도로의 기본 통행 후보로 취급한다.
- no/private 및 허가·업무 전용 값(agricultural, forestry, military, official, permit, residents, delivery, bus)은 일반 승용차 대상에서 제외한다.
- destination/customers는 목적지 접근을 구현하기 전까지 보류한다. 그 외 미해석 접근 값도 보류한다.
- 차량 접근 관련 방향별·조건부·차로별 태그는 보류한다. 단순 설명인 `*:note`는 판정에 사용하지 않는다. 더 구체적인 허용값이 있더라도 미지원 modifier가 있으면 보류하는 보수적 정책이다.
- 방향 태그는 oneway:motorcar → oneway:motor_vehicle → oneway 순서다. yes/true/1은 정방향, -1은 역방향, no/false/0은 양방향이다.
- 방향 태그가 없을 때 motorway와 junction=roundabout은 정방향으로 처리한다. 그 외 범위 안 도로는 양방향 후보로 처리한다. 명시적 oneway=no는 이 기본값을 덮어쓴다.
- oneway:bicycle, oneway:bus는 승용차 판정에서 무시하고, 나머지 미지원 oneway modifier와 reversible 같은 미해석 값은 보류한다.
- 속도 계산, 노드 barrier/access, 차량 높이·폭·중량, 페리, 계절·시간 조건은 아직 구현하지 않았다. 따라서 실제 운행용 프로필로 사용할 단계가 아니다.

접근 태그와 방향 의미의 근거는 [OSM access 문서](https://wiki.openstreetmap.org/wiki/Key:access),
[OSM oneway 문서](https://wiki.openstreetmap.org/wiki/Key:oneway)다.
도로 범위와 보류·제외 방침은 이 프로젝트의 초기 지원 정책이다.

## 회전 제한 검증

`CarDataAnalysis`는 두 번 순회한다. 첫 순회에서 도로 판정과 회전 제한 멤버를 수집하고,
두 번째 순회에서 제한이 참조하는 Way의 노드 배열과 Node/Relation 존재 여부를 확인한다.
따라서 PBF 안의 엔티티 순서에 의존하지 않는다.

`RestrictionValidator`의 검사 항목:

- 실제 입력에 멤버의 ID·종류가 존재하는지 확인한다.
- 이번 지원 범위는 from Way 1개, to Way 1개, via Node 1개다.
- via Way 연쇄는 멤버 순서대로 연결성과 통행 방향을 검사한다. 검사에 통과해도 실제 탐색 전이 적용 전까지 `VIA_WAY_DEFERRED`로 분류한다.
- 역할명 누락·알 수 없는 역할·잘못된 멤버 종류·복수 from/to는 `INVALID_MEMBERS`로 분류한다. 이는 이번 지원 구조에 맞지 않는다는 뜻이며 모든 경우가 OSM 원본 오류인 것은 아니다.
- via Node가 from/to Way 양쪽에 포함되는지 확인하고, 판정된 일방통행 방향에서 진입·진출이 가능한지 검사한다.
- restriction:motorcar → restriction:motor_vehicle → restriction:vehicle → restriction 순서로 값을 선택한다.
- no_left_turn/no_right_turn/no_straight_on/no_u_turn, only_left_turn/only_right_turn/only_straight_on을 인식한다.
- 조건부 제한은 보류한다. except의 motorcar/motor_vehicle/vehicle은 자동차 예외로 분리하고, 알려진 다른 차종 예외는 무시한다. 알 수 없는 except 값은 보류한다.
- type=restriction 및 지원하는 차량별 restriction:* 형태를 검사한다. 다른 type 변형은 미지원으로 분류한다.

검사 사유가 없는 관계를 **노드 경유 후보**로 집계한다. 아직 회전 전이 생성, 각도 검증,
분할된 도로·중간 노드 처리, only_* 제한의 실제 적용까지 완료한 것은 아니다.
사유별 수치는 관계 단위이며 한 관계가 여러 사유에 포함될 수 있다.

구조와 태그 의미는 [OSM 회전 제한 문서](https://wiki.openstreetmap.org/wiki/Relation:restriction)를 참고했다.

## 서울 입력 결과

입력: `processed/seoul-routing.osm.pbf`, 전체 highway Way 266,856개.
실행: backend에서 `./gradlew :routing-core:analyzeOsm`.

| 도로 판정 | 개수 |
| --- | ---: |
| 수락 후보 | 170,086 |
| 보류 | 100 |
| 제외 | 96,670 |
| 후보 도로의 고유 노드 ID | 799,512 |
| 방향을 반영한 연속 노드 간선 후보 | 1,597,408 |

연속된 동일 노드 ID 사이의 self-loop는 간선 수에서 제외한다. 서로 다른 Way의 중복 간선은 합치지 않았다.
고유 노드 수는 참조 ID 수이며, 최종 그래프의 교차점 수가 아니다.

| 회전 제한 검사 | 관계 수 |
| --- | ---: |
| 전체 | 6,393 |
| 노드 경유 후보 | 4,964 |
| 멤버 구조가 지원 범위 밖 | 744 |
| 방향 충돌 | 343 |
| via-way 보류 | 177 |
| 참조 도로가 자동차 후보에 포함되지 않음 | 146 |
| 노드 또는 via-way 연쇄 연결 불일치 | 72 |
| 미지원·누락 restriction 값 | 4 |
| 참조 멤버 누락 | 3 |
| 조건부 제한 | 3 |

누락 참조를 가진 회전 제한 ID: 11909875, 11909876, 20284545.
다른 사유별 예시 ID와 원시 통계는 [seoul-analysis.txt](seoul-analysis.txt)에 있다.
오류·미지원 제한을 조용히 버린 채 그래프를 운영해서는 안 된다. 각 사유를 검토하고
입력 거부 또는 관련 전이 차단 등 처리 방침을 정하는 작업이 남아 있다.

## 메모리 재산정

후보 N=799,512, E=1,597,408로 이전의 기본 배열 가정(좌표 int 2개, offset int,
간선 목적지·거리·시간 int 3개)을 적용하면 `12N + 4 + 12E` = 28,763,044 bytes, **27.43MiB**다.
요청별 `double 비용 + int 이전 노드 + byte 방문 상태` 가정은 **9.91MiB/요청**이다.
이는 회전 제한에 필요한 간선별 상태, 공간 인덱스, 큐, 원본 ID 매핑과 JVM 비용을 제외한 산술값이다.
최종 적재 구조나 메모리 사용량의 실측값이 아니다.

이번 분석은 `-Xmx512m`으로 성공했다. 원시 통계와 자동차 분석을 합친 코드 구간은 단일 실행에서 약 2.627초였다.
분석기는 전체 그래프 객체 대신 후보 참조 ID 배열, 제한 관계 및 제한이 참조하는 Way 배열을 유지한다.

## 다음 작업

1. 보류·연결 불일치·방향 충돌 관계의 예시를 살펴 지원 범위와 차단 정책을 확정한다.
2. 교차점과 도로 형상 중간 노드를 구분하고 회전 전이를 포함한 그래프 스키마를 설계한다.
3. 차량 프로필 버전 및 지원하지 않는 규칙을 가공 파일 메타데이터에 기록한다.
4. 그래프 파일 생성과 서버 로더를 구현하고 실제 메모리·적재 시간을 측정한다.

9번 정합성 검증의 상세 사례와 처리 방침은 [RESTRICTION_REVIEW.md](RESTRICTION_REVIEW.md)를 참고한다.
