/** Shared algorithm labels, explanations and map styles. */
export const algorithms = ["dijkstra", "astar", "alt"] as const;
export type Algorithm = typeof algorithms[number];
export const algorithmInfo = {
  dijkstra: {
    label: "Dijkstra", name: "Dijkstra", colorClass: "blue", color: "#2563eb", pointColor: "#2563eb", description: "비용이 작은 순서로", width: 8,
    priority: "지금까지 이동한 거리",
    explanation: "출발지에서 지금까지 이동한 거리가 가장 짧은 후보부터 탐색합니다. 목적지까지 남은 거리는 탐색 순서에 반영하지 않습니다.",
    tradeoff: "남은 거리 추정이 필요 없어 다른 알고리즘의 비교 기준으로 쓰입니다. 목적지 반대 방향의 후보도 살펴보므로 탐색 범위가 넓어질 수 있습니다.",
  },
  astar: {
    label: "A*", name: "A*", colorClass: "orange", color: "#c65d21", pointColor: "#ed772c", description: "목적지 방향을 고려", width: 5,
    priority: "지금까지 이동한 거리 + 남은 거리 추정값",
    explanation: "지금까지 이동한 거리에 목적지까지 남은 거리의 추정값을 더해, 합이 작은 후보부터 탐색합니다. 이 데모는 실제 남은 거리보다 크지 않도록 보정한 직선거리를 활용합니다.",
    tradeoff: "목적지에 도달하기 유리한 후보를 먼저 살펴 탐색 범위를 줄일 수 있습니다. 도로가 크게 우회하는 구간에서는 직선거리 추정의 효과가 작아질 수 있습니다.",
  },
  alt: {
    label: "ALT", name: "랜드마크 A* (ALT)", colorClass: "green", color: "#15803d", pointColor: "#16a34a", description: "미리 계산한 랜드마크 거리 활용", width: 2.5,
    priority: "지금까지 이동한 거리 + 랜드마크를 활용한 남은 거리 추정값",
    explanation: "A*의 남은 거리 추정을 강화한 방식입니다. 여러 기준점(랜드마크)과 각 지점 사이의 도로 거리를 미리 계산하고, 거리 차이와 삼각부등식으로 남은 거리의 하한을 구합니다. 이 데모는 직선거리 하한과 비교해 더 큰 값을 사용합니다.",
    tradeoff: "하한은 실제 남은 거리보다 크지 않은 값입니다. 도로 연결 정보를 활용해 탐색을 더 줄일 수 있지만, 거리표를 만드는 시간과 저장할 메모리가 필요합니다. 표시된 탐색 시간에는 이 전처리 시간이 포함되지 않습니다.",
  },
} satisfies Record<Algorithm, {
  label: string; name: string; colorClass: string; color: string; pointColor: string; description: string; width: number;
  priority: string; explanation: string; tradeoff: string;
}>;
