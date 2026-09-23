# OSM Data

경로탐색 구현 및 테스트를 위한 OpenStreetMap(OSM) 데이터를 관리하는 디렉터리입니다.

PBF 데이터는 용량이 크기 때문에 Git 저장소에는 포함하지 않으며, 원본 데이터를 내려받은 후 `osmium-tool`을 이용하여 필요한 영역만 추출하여 사용합니다.

## 디렉터리 구조

```text
data/
├── raw/
│   └── south-korea-260919.osm.pbf
├── processed/
│   └── seoul-routing.osm.pbf
└── README.md
```

* `raw/`: 다운로드한 원본 OSM PBF 데이터
* `processed/`: 경로탐색 구현 및 테스트를 위해 가공한 PBF 데이터

PBF 파일은 `.gitignore`를 통해 Git 저장소에서 제외합니다.

---

## 1. 원본 데이터 준비

South Korea OSM PBF 데이터를 다운로드합니다.

사용 데이터:

```text
south-korea-260919.osm.pbf (https://download.geofabrik.de/asia/south-korea.html)
```

다운로드한 파일을 다음 경로에 저장합니다.

```text
backend/data/raw/south-korea-260919.osm.pbf
```

---

## 2. osmium-tool 설치

Windows 환경에서는 WSL(Windows Subsystem for Linux)을 이용하여 `osmium-tool`을 실행합니다.

### WSL 설치

관리자 권한 PowerShell에서 실행합니다.

```powershell
wsl --install
```

설치 완료 후 Ubuntu를 실행합니다. (PowerShell 내부에서 $wsl)

### osmium-tool 설치

Ubuntu 터미널에서 다음 명령을 실행합니다.

```bash
sudo apt update
sudo apt install osmium-tool
```

맥 기준

```bash
brew update
brew install osmium-tool
```

설치 여부를 확인합니다.

```bash
osmium --version
```

---

## 3. 서울 지역 데이터 추출

전체 South Korea 데이터에서 경로탐색 구현에 사용할 서울 및 인접 영역을 추출합니다.

프로젝트 루트로 이동합니다.

Windows 프로젝트 경로가 다음과 같은 경우:

```text
C:\Users/USER/IdeaProjects/routing-engine
```

WSL에서는 다음 경로로 접근할 수 있습니다.

```bash
cd /mnt/c/Users/USER/IdeaProjects/routing-engine
```

이후 `osmium extract`를 실행합니다.

```bash
osmium extract \
  -b 126.70,37.35,127.25,37.80 \
  backend/data/raw/south-korea-260919.osm.pbf \
  -o backend/data/processed/seoul-routing.osm.pbf
```

Bounding Box는 다음 순서로 지정합니다.

```text
min_lon,min_lat,max_lon,max_lat
```

현재 프로젝트에서는 서울 경계 부근의 도로 연결성을 고려하여 서울 행정구역보다 넓은 범위를 사용합니다.

```text
West  : 126.70
South : 37.35
East  : 127.25
North : 37.80
```

---

## 4. 생성 결과 확인

가공된 데이터는 다음 위치에 생성됩니다.

```text
backend/data/processed/seoul-routing.osm.pbf
```

파일 정보를 확인하려면 다음 명령을 사용할 수 있습니다.

```bash
osmium fileinfo -e backend/data/processed/seoul-routing.osm.pbf
```

---

## 5. Git 관리

OSM PBF 파일은 대용량 바이너리 데이터이므로 Git 저장소에 포함하지 않습니다.

프로젝트 `.gitignore`에 다음 항목을 추가합니다.

```gitignore
# OSM PBF Data
backend/data/raw/*.pbf
backend/data/processed/*.pbf
```

따라서 Git 저장소에는 데이터 파일 자체가 아닌 **원본 데이터 준비 및 가공 과정만 기록하여 동일한 개발 데이터를 재생성할 수 있도록 관리합니다.**

---

## 데이터 처리 흐름

```text
South Korea OSM PBF
        │
        │ osmium extract
        ▼
 Seoul Routing PBF
        │
        │ PBF Parsing
        ▼
    OSM Node / Way
        │
        │ Road Filtering
        ▼
      Node / Link
        │
        ▼
   Routing Graph
        │
        ▼
  Routing Algorithm
```

`seoul-routing.osm.pbf`는 이후 도로 데이터 파싱 및 경로탐색 그래프 구축을 위한 입력 데이터로 사용합니다.

## Java 분석기

`backend` 디렉터리에서 `./gradlew :routing-core:analyzeOsm`을 실행하면
서울 추출본의 노드·도로 태그·회전 제한·고유 도로 노드 참조 통계를 출력합니다.
일반 테스트는 `./gradlew test`로 실행하며 대용량 PBF 없이 동작합니다.

분석 수치와 메모리 구조 검토 내용은 [ANALYSIS.md](ANALYSIS.md)를 참고하세요.

자동차 후보 판정 및 회전 제한 검사 정책과 결과는 [CAR_PROFILE.md](CAR_PROFILE.md)에 정리했습니다.

## 서울 회전 제한 참조 보충

동일한 전국 원본에서 확인한 누락 Way 3개와 참조 Node를 별도로 추출해 병합했습니다.
기존 `seoul-routing.osm.pbf`는 유지하고 `seoul-routing-complete.osm.pbf`를 생성합니다.
이름의 complete는 이번 회전 제한 참조 보충을 뜻하며, 모든 데이터 오류가 해결됐다는 뜻은 아닙니다.

재현 명령 (프로젝트 루트 기준, 생성 대상 파일이 없는 상태):

```sh
osmium getid backend/data/raw/south-korea-260919.osm.pbf \
  w468202418 w468202416 w1460674135 --add-referenced \
  -o backend/data/processed/seoul-restriction-supplement.osm.pbf
osmium merge backend/data/processed/seoul-routing.osm.pbf \
  backend/data/processed/seoul-restriction-supplement.osm.pbf \
  -o backend/data/processed/seoul-routing-complete.osm.pbf
osmium check-refs backend/data/processed/seoul-routing-complete.osm.pbf
./backend/gradlew -p backend :routing-core:analyzeOsm \
  -Ppbf=data/processed/seoul-routing-complete.osm.pbf
```

이 ID 목록은 현재 스냅샷에서 확인한 3건에만 해당합니다. 다른 데이터셋에는 다시 누락 검사를 해야 합니다.
분석 명령의 기본 입력은 기존 파일이므로 보완본은 위처럼 `-Ppbf`로 지정합니다.
검증 결과는 [seoul-complete-analysis.txt](seoul-complete-analysis.txt)에 보관합니다.
바이너리 데이터는 `.gitignore`로 제외됩니다.
