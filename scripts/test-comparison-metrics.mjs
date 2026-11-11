import assert from 'node:assert/strict';
import { speedup, reduction, formatSpeedup, formatReduction } from '../frontend/src/comparison-metrics.ts';

assert.equal(speedup(100, 25), 4);
assert.equal(speedup(25, 100), .25);
assert.equal(reduction(100, 25), 75);
assert.equal(reduction(100, 150), -50);
assert.equal(reduction(100, 0), 100);
for (const value of [0, -1, NaN, Infinity, undefined]) {
  assert.equal(speedup(value, 1), null);
  assert.equal(speedup(1, value), null);
  assert.equal(reduction(value, 1), null);
}
for (const value of [-1, NaN, Infinity, undefined]) assert.equal(reduction(1, value), null);
assert.equal(speedup(Number.MAX_VALUE, Number.MIN_VALUE), null);
assert.equal(reduction(Number.MIN_VALUE, Number.MAX_VALUE), null);
assert.equal(formatSpeedup(.25), '0.25×');
assert.equal(formatReduction(-50), '-50.0%');
assert.equal(formatSpeedup(null), '계산 불가');
assert.equal(formatReduction(null), '계산 불가');
console.log('Comparison metrics PASS: speedup, reductions, regression, zero and unavailable values');
