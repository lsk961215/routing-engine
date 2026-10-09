// node scripts/check-benchmark-report.cjs .runtime/benchmarks/<run>/report.json
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');

(async () => {
  assert.ok(process.argv[2], 'Pass the generated report.json path');
  const filename = path.resolve(process.argv[2]);
  const report = JSON.parse(fs.readFileSync(filename, 'utf8'));
  const { measuredRounds, forks } = report.settings;
  assert.equal(report.forks.length, forks);
  const summaryCheck = (samples, summaries) => {
    for (const summary of summaries) {
      const selected = samples.filter(s => s.queryId === summary.queryId && s.algorithm === summary.algorithm);
      for (const [key, source, predicate] of [
        ['searchMillis', 'searchMillis', () => true], ['firstMillis', 'searchMillis', s => s.position === 1], ['secondMillis', 'searchMillis', s => s.position === 2],
        ...(report.version >= 2 ? [
          ['allocatedBytes', 'allocatedBytes', () => true], ['firstAllocatedBytes', 'allocatedBytes', s => s.position === 1], ['secondAllocatedBytes', 'allocatedBytes', s => s.position === 2],
        ] : []),
      ]) {
        const values = selected.filter(predicate).map(s => s[source]).filter(value => value != null).sort((a,b) => a-b);
        const stats = summary[key], count = values.length;
        if (!count) { assert.equal(stats, null); continue; }
        assert.ok(values.every(value => Number.isFinite(value) && value >= 0));
        assert.equal(stats.count, count);
        assert.equal(stats.median, count % 2 ? values[Math.floor(count/2)] : (values[count/2-1]+values[count/2])/2);
        assert.equal(stats.p95, values[Math.ceil(count*.95)-1]);
        assert.equal(stats.min, values[0]); assert.equal(stats.max, values.at(-1));
        const mean = values.reduce((sum,value) => sum+value,0)/count;
        const deviation = Math.sqrt(values.reduce((sum,value) => sum+(value-mean)**2,0)/count);
        assert.ok(Math.abs(stats.mean-mean) <= Math.max(1e-8,Math.abs(mean)*1e-12));
        assert.ok(Math.abs(stats.standardDeviation-deviation) <= Math.max(1e-8,deviation*1e-12));
      }
      assert.equal(summary.firstMillis.count, summary.secondMillis.count);
    }
  };
  for (const fork of report.forks) {
    assert.equal(fork.samples.length, report.querySet.queries.length * measuredRounds * 2);
    assert.equal(fork.graphSha256, report.forks[0].graphSha256);
    assert.equal(fork.queriesSha256, report.forks[0].queriesSha256);
    assert.ok(fork.samples.every(s => s.round >= 1 && s.round <= measuredRounds && s.fork === fork.fork));
    for (const query of report.querySet.queries) {
      const leaders = [];
      for (let round = 1; round <= measuredRounds; round++) {
        const pair = fork.samples.filter(s => s.queryId === query.id && s.round === round).sort((a,b) => a.position-b.position);
        assert.equal(pair.length, 2);
        assert.deepEqual(pair.map(s => s.position), [1,2]);
        assert.notEqual(pair[0].algorithm, pair[1].algorithm);
        assert.equal(pair[0].snapMillis, pair[1].snapMillis);
        assert.equal(pair[0].code, pair[1].code);
        if (pair[0].code === 'Ok') assert.ok(Math.abs(pair[0].distanceMetres-pair[1].distanceMetres) < 1e-6);
        leaders.push(pair[0].algorithm);
      }
      for (let i=1; i<leaders.length; i++) assert.notEqual(leaders[i-1],leaders[i]);
    }
    summaryCheck(fork.samples, fork.summaries);
  }
  summaryCheck(report.forks.flatMap(f => f.samples), report.summaries);

  const browser = await chromium.launch({headless:true});
  try {
    const page = await browser.newPage({viewport:{width:1440,height:1100}});
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    await page.goto(pathToFileURL(path.join(path.dirname(filename), 'report.html')).href);
    assert.equal(await page.locator('#rows tr').count(), report.querySet.queries.length*2);
    const count = async () => Number(await page.locator('#rows tr').first().locator('td').nth(2).textContent());
    assert.equal(await count(), measuredRounds*forks);
    assert.equal(await page.locator('#chart rect').count(), 4);
    await page.screenshot({path:'/tmp/routing-benchmark-report.png',fullPage:true});
    await page.selectOption('#position','first');
    assert.equal(await count(), measuredRounds*forks/2);
    await page.selectOption('#fork',String(report.forks[0].fork));
    assert.equal(await count(), measuredRounds/2);
    await page.selectOption('#position','second');
    assert.equal(await count(), measuredRounds/2);
    await page.selectOption('#query',report.querySet.queries.at(-1).id);
    assert.ok((await page.locator('#chart').textContent()).includes('ms'));
    if (report.version >= 2) {
      await page.selectOption('#metric','allocation');
      assert.ok((await page.locator('#chart').textContent()).includes('MiB'));
      const format = value => (value/1048576).toLocaleString('ko-KR',{minimumFractionDigits:2,maximumFractionDigits:2});
      const firstQuery = report.querySet.queries[0].id;
      for (const forkValue of ['all',String(report.forks[0].fork)]) {
        await page.selectOption('#fork',forkValue);
        const summaries = forkValue==='all'?report.summaries:report.forks[0].summaries;
        const summary = summaries.find(s => s.queryId===firstQuery && s.algorithm==='dijkstra');
        for (const [position,field,timeField] of [['all','allocatedBytes','searchMillis'],['first','firstAllocatedBytes','firstMillis'],['second','secondAllocatedBytes','secondMillis']]) {
          await page.selectOption('#position',position);
          const cells = page.locator('#rows tr').first().locator('td'), stats = summary[field];
          assert.equal(await cells.nth(2).textContent(),`${stats?.count??0} / ${summary[timeField].count}`);
          assert.equal(await cells.nth(3).textContent(),stats?format(stats.median):'측정 불가');
        }
      }
      await page.screenshot({path:'/tmp/routing-benchmark-allocation.png',fullPage:true});
      // Missing allocation counters must render as unavailable, never as zero.
      await page.evaluate(() => {
        for (const summary of report.forks[0].summaries) summary.secondAllocatedBytes=null;
        render();
      });
      assert.equal(await page.locator('#rows tr').first().locator('td').nth(3).textContent(),'측정 불가');
      assert.equal(await page.locator('#chart rect').count(),0);
      await page.selectOption('#fork','all');
      await page.selectOption('#position','all');
    }
    await page.setViewportSize({width:390,height:844});
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.screenshot({path:'/tmp/routing-benchmark-report-mobile.png',fullPage:true});
    assert.deepEqual(errors,[]);
    console.log(`Benchmark PASS: ${forks} JVMs, ${report.forks.flatMap(f=>f.samples).length} measured samples, balanced order, raw statistics, report filters and mobile`);
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
