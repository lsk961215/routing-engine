import { Map, Popup, type MapMouseEvent, type GeoJSONSource } from 'maplibre-gl';
import type { FeatureCollection } from 'geojson';

const reasonLabels: Record<string, string> = {
  explicit_car_access_no: '명시적인 자동차 접근 금지',
  node_access_or_barrier: '미지원 장벽·접근 제한 노드',
  quarantined_node: '제외 대상으로 지정된 노드',
  quarantined_member: '멤버 도로 제외에 따른 연쇄 제외',
  ambiguous_geometry: '해석이 모호한 도로 형상',
  MISSING_REFERENCE: '참조 객체 누락', INVALID_MEMBERS: '멤버 구성 오류',
  MISSING_FROM: '진입 도로 누락', MISSING_TO: '진출 도로 누락', MISSING_VIA: '경유 지점 누락',
  DISCONNECTED_VIA: '경유 지점 연결 불일치', DIRECTION_CONFLICT: '통행 방향 충돌',
  WAY_NOT_ACCEPTED: '멤버 도로의 자동차 프로필 미통과', CONDITIONAL: '조건부 제한',
  UNKNOWN_ROLE: '알 수 없는 멤버 역할', UNSUPPORTED_VALUE: '미지원 제한 값',
  UNSUPPORTED_TYPE: '미지원 제한 유형', UNSUPPORTED_EXCEPTION: '미지원 예외',
  EXEMPT_CAR: '자동차 예외', DUPLICATE_MEMBER: '멤버 중복', VIA_WAY_DEFERRED: '경유 도로 방식',
};

export function setupExcludedRoads(map: Map): (event: MapMouseEvent) => boolean {
  const toggle = document.querySelector<HTMLInputElement>('#show-exclusions')!;
  const status = document.querySelector<HTMLElement>('#exclusion-status')!;
  let pending: AbortController | null = null;
  let popup: Popup | null = null;
  const empty: FeatureCollection = { type: 'FeatureCollection', features: [] };
  const clear = () => (map.getSource('excluded-roads') as GeoJSONSource | undefined)?.setData(empty);
  const invalidate = () => { pending?.abort(); pending = null; clear(); popup?.remove(); };

  async function refresh() {
    if (!toggle.checked || !map.getSource('excluded-roads')) return;
    invalidate();
    const request = new AbortController(); pending = request;
    status.textContent = '제외 도로를 불러오는 중…';
    const bounds = map.getBounds();
    const params = new URLSearchParams({
      west: String(Math.max(-180, bounds.getWest())), south: String(Math.max(-90, bounds.getSouth())),
      east: String(Math.min(180, bounds.getEast())), north: String(Math.min(90, bounds.getNorth())),
    });
    try {
      const response = await fetch(`/api/excluded-roads?${params}`, { signal: request.signal });
      if (!response.ok) throw new Error(response.status === 503 ? '제외 도로 데이터가 준비되지 않았습니다.' : '제외 도로를 불러오지 못했습니다. 지도를 이동해 다시 시도하세요.');
      const data: FeatureCollection = await response.json();
      if (pending !== request || !toggle.checked) return;
      (map.getSource('excluded-roads') as GeoJSONSource).setData(data);
      status.textContent = `현재 영역 ${data.features.length.toLocaleString()}개`;
    } catch (error) {
      if (!request.signal.aborted && pending === request) status.textContent = error instanceof Error ? error.message : '조회 실패';
    } finally { if (pending === request) pending = null; }
  }
  map.on('load', () => {
    map.addSource('excluded-roads', { type: 'geojson', data: empty });
    map.addLayer({ id: 'excluded-roads', type: 'line', source: 'excluded-roads',
      layout: { visibility: 'none' }, paint: {
        'line-color': ['match', ['get', 'category'], 'direct', '#dc2626', '#d97706'],
        'line-width': ['interpolate', ['linear'], ['zoom'], 10, 2, 16, 6], 'line-opacity': 0.85,
      } });
    toggle.disabled = false;
  });
  toggle.addEventListener('change', () => {
    invalidate();
    map.setLayoutProperty('excluded-roads', 'visibility', toggle.checked ? 'visible' : 'none');
    status.textContent = '';
    if (toggle.checked) void refresh();
  });
  map.on('movestart', () => { if (toggle.checked) invalidate(); });
  map.on('moveend', () => { void refresh(); });
  return event => {
    if (!toggle.checked || !map.getLayer('excluded-roads')) return false;
    const features = map.queryRenderedFeatures(event.point, { layers: ['excluded-roads'] });
    if (!features.length) return false;
    const p = features[0].properties;
    const content = document.createElement('section');content.className = 'exclusion-detail';
    const title = document.createElement('strong');title.textContent = `${p.name || '이름 없는 도로'} · Way ${p.wayId}`;
    content.append(title);
    const category = document.createElement('p');category.textContent = p.category === 'direct' ? '자동차 통행 금지' : '연쇄 제외';content.append(category);
    const array = (value: unknown): string[] => typeof value === 'string' ? JSON.parse(value) : value as string[];
    const list = document.createElement('ul');
    for (const reason of array(p.reasons)) {
      const li = document.createElement('li');
      li.textContent = reason.replace(/relation:([0-9]+):/g, '회전 제한 $1:').replace(/[A-Za-z_]+/g, key => reasonLabels[key] ?? key);
      list.append(li);
    }
    content.append(list);
    for (const [label, value] of [['관련 노드', p.nodeIds], ['관련 회전 제한', p.relationIds]]) {
      const ids = array(value);
      if (ids.length) { const row = document.createElement('p');row.textContent = `${label}: ${ids.join(', ')}`;content.append(row); }
    }
    popup?.remove();popup = new Popup({ maxWidth: '380px' }).setLngLat(event.lngLat).setDOMContent(content).addTo(map);
    return true;
  };
}
