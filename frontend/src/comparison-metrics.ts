/** A ratio below one means slower; negative reduction means more work than the baseline. */
export function speedup(baseline: number, candidate: number): number | null {
  if (!Number.isFinite(baseline) || !Number.isFinite(candidate) || baseline <= 0 || candidate <= 0) return null;
  const value = baseline / candidate;
  return Number.isFinite(value) ? value : null;
}
export function reduction(baseline: number, candidate: number): number | null {
  if (!Number.isFinite(baseline) || !Number.isFinite(candidate) || baseline <= 0 || candidate < 0) return null;
  const value = (1 - candidate / baseline) * 100;
  return Number.isFinite(value) ? value : null;
}
export const formatSpeedup = (value: number | null) => value === null ? "계산 불가" : `${value.toFixed(2)}×`;
export const formatReduction = (value: number | null) => value === null ? "계산 불가" : `${value.toFixed(1)}%`;
