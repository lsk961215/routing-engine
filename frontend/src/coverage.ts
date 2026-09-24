import type { Map } from 'maplibre-gl';

// Same extraction box as backend/data/README.md, not an administrative boundary or hard API geofence.
export const SEOUL_BOUNDS: [[number, number], [number, number]] = [[126.70, 37.35], [127.25, 37.80]];
export function setupCoverage(map: Map) {
  const [[west, south], [east, north]] = SEOUL_BOUNDS;
  const ring = [[west,south],[east,south],[east,north],[west,north],[west,south]];
  const show = document.querySelector<HTMLInputElement>('#show-coverage')!;
  const overview = document.querySelector<HTMLButtonElement>('#coverage-overview')!;
  map.on('load', () => {
    map.addSource('coverage-mask', { type: 'geojson', data: { type: 'Feature', properties: {}, geometry: {
      type: 'Polygon', coordinates: [[[-180,-85],[180,-85],[180,85],[-180,85],[-180,-85]], [...ring].reverse()],
    } } });
    map.addSource('coverage-boundary', { type: 'geojson', data: { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: ring } } });
    // Above the basemap, below exclusion/route overlays. Source data never affects routing.
    map.addLayer({ id: 'coverage-mask', type: 'fill', source: 'coverage-mask', paint: { 'fill-color': '#334155', 'fill-opacity': 0.22 } }, 'excluded-roads');
    map.addLayer({ id: 'coverage-boundary', type: 'line', source: 'coverage-boundary', paint: { 'line-color': '#6366f1', 'line-width': 3, 'line-dasharray': [3,2] } }, 'excluded-roads');
    show.disabled = false; overview.disabled = false;
  });
  show.addEventListener('change', () => {
    for (const id of ['coverage-mask','coverage-boundary']) map.setLayoutProperty(id,'visibility',show.checked ? 'visible' : 'none');
  });
  overview.addEventListener('click', () => map.fitBounds(SEOUL_BOUNDS, { padding: 45, duration: 0 }));
}
