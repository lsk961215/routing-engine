/** Shared legend and paint definitions for routes, expansion points and controls. */
export const algorithms = ["dijkstra", "astar", "alt"] as const;
export type Algorithm = typeof algorithms[number];
export const algorithmInfo = {
  dijkstra: { label: "Dijkstra", name: "Dijkstra", colorClass: "blue", color: "#2563eb", pointColor: "#2563eb", description: "비용이 작은 순서로", width: 8 },
  astar: { label: "A*", name: "A*", colorClass: "orange", color: "#c65d21", pointColor: "#ed772c", description: "목적지 방향을 고려", width: 5 },
  alt: { label: "ALT", name: "랜드마크 A* (ALT)", colorClass: "green", color: "#15803d", pointColor: "#16a34a", description: "미리 계산한 랜드마크 거리 활용", width: 2.5 },
} satisfies Record<Algorithm, { label: string; name: string; colorClass: string; color: string; pointColor: string; description: string; width: number }>;
