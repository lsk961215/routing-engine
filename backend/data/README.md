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
south-korea-260919.osm.pbf
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
