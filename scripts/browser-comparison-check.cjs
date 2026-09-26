// Requires the Seoul API, Vite and Playwright available to Node.
const { selectTestRoute } = require('./browser-route-helpers.cjs');
const { chromium } = require('playwright');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({headless:true,args:['--enable-webgl','--use-gl=angle','--use-angle=swiftshader']});
  try {
    const page = await browser.newPage({viewport:{width:1440,height:1000}});
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/src/main.ts*', async route => {
      const response = await route.fetch();
      await route.fulfill({response,body:(await response.text())+'\nwindow.__routeTestMap=map;\n'});
    });
    await page.goto('http://127.0.0.1:5173');
    await page.waitForFunction(() => !document.querySelector('#select-start').disabled);
    const selectMode = async mode => page.locator('#algorithm label').filter({has:page.locator(`input[value=${mode}]`)}).click();
    const visible = async () => page.waitForFunction(() => !document.querySelector('#comparison-info').classList.contains('hidden'));
    const noRoutes = async () => {
      assert.equal(await page.evaluate(() => ['route','route-dijkstra','route-astar'].some(id => window.__routeTestMap.getLayer(id))), false);
      assert.ok(await page.locator('#comparison-info').evaluate(e => e.classList.contains('hidden')));
    };
    await selectMode('compare');
    const pending = page.waitForResponse(r => r.url().includes('/api/compare?'));
    await selectTestRoute(page);
    const response = await pending;
    assert.equal(response.status(),200);
    const data = await response.json();
    assert.deepEqual(data.results.map(r => r.algorithm),['dijkstra','astar']);
    assert.ok(Math.abs(data.results[0].routes[0].distance-data.results[1].routes[0].distance)<1e-6);
    await visible();
    const originalStart = await page.textContent('#start-road');
    const originalEnd = await page.textContent('#end-road');
    for (const result of data.results) {
      const geometry = await page.evaluate(async id => (await window.__routeTestMap.getSource(id).getData()).geometry, `route-${result.algorithm}`);
      assert.deepEqual(geometry,result.routes[0].geometry);
      assert.equal(await page.textContent(`#${result.algorithm}-time`),result.metrics.searchMillis.toFixed(2));
    }
    assert.match(await page.textContent('#comparison-snap'),/공통 좌표 연결 .* ms/);
    await page.uncheck('#show-astar');
    assert.equal(await page.evaluate(() => window.__routeTestMap.getLayoutProperty('route-astar','visibility')),'none');
    assert.ok(await page.evaluate(() => window.__routeTestMap.getLayer('route-dijkstra')));
    await page.check('#show-astar');
    await page.uncheck('#show-dijkstra');
    assert.equal(await page.evaluate(() => window.__routeTestMap.getLayoutProperty('route-dijkstra','visibility')),'none');
    await page.check('#show-dijkstra');
    await page.waitForFunction(() => ['route-dijkstra','route-astar'].every(id => window.__routeTestMap.queryRenderedFeatures({layers:[id]}).length>0));
    await page.screenshot({path:'/tmp/routing-comparison.png'});
    await page.setViewportSize({width:390,height:844});
    await page.locator('#comparison-info').scrollIntoViewIfNeeded();
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth<=innerWidth));
    await page.screenshot({path:'/tmp/routing-comparison-mobile.png'});
    await page.setViewportSize({width:1440,height:1000});

    // A comparison with no directed route still displays both measured results.
    await page.route('**/api/compare?*', route => route.fulfill({json:{...data,results:data.results.map(r => ({...r,code:'NoRoute',routes:[]}))}}));
    await page.click('#search-route'); await visible();
    assert.equal(await page.textContent('#dijkstra-code'),'경로 없음');
    assert.ok(await page.locator('#show-dijkstra').isDisabled());
    assert.equal(await page.evaluate(() => !!window.__routeTestMap.getLayer('route-dijkstra')),false);
    await page.unroute('**/api/compare?*');

    // Service errors clear previous results and allow another request.
    await page.route('**/api/compare?*', route => route.fulfill({status:503,body:''}));
    await page.click('#search-route');
    await page.waitForFunction(() => document.querySelector('#routing-status').textContent.includes('준비되지'));
    await noRoutes();
    assert.equal(await page.locator('#search-route').isDisabled(),false);
    await page.unroute('**/api/compare?*');

    // Delayed comparison responses must not reappear after cancellation or a mode change.
    for (const action of ['cancel','mode','reset']) {
      let release, started;
      const gate = new Promise(resolve => { release=resolve; });
      const seen = new Promise(resolve => { started=resolve; });
      await page.route('**/api/compare?*', async route => {
        started(); await gate; await route.fulfill({json:data}).catch(() => {});
      });
      await page.click('#search-route'); await seen;
      assert.ok(await page.locator('#search-route').isDisabled());
      if (action==='cancel') await page.click('#cancel-route');
      if (action==='mode') await selectMode('astar');
      if (action==='reset') await page.click('#reset-points');
      release(); await page.waitForTimeout(200); await noRoutes();
      await page.unroute('**/api/compare?*');
      if (action!=='reset') {
        assert.equal(await page.textContent('#start-road'),originalStart);
        assert.equal(await page.textContent('#end-road'),originalEnd);
        await selectMode('compare');
      }
    }
    assert.equal(await page.locator('.maplibregl-marker').count(),0);
    assert.deepEqual(errors,[]);
    console.log('Comparison PASS: shared results, both geometries, visibility, mobile, no route, errors, cancellation, stale responses');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
