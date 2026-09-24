const { chromium } = require('playwright');
const assert = require('node:assert/strict');
(async () => {
  const browser = await chromium.launch({headless:true,args:['--enable-webgl','--use-gl=angle','--use-angle=swiftshader']});
  try {
    const page=await browser.newPage({viewport:{width:1280,height:900}});
    const errors=[];page.on('pageerror',e=>errors.push(e.message));
    await page.route('**/src/main.ts*',async route=>{const response=await route.fetch();await route.fulfill({response,body:(await response.text())+'\nwindow.__routeTestMap = map;\n'});});
    await page.goto('http://localhost:5173');
    await page.waitForFunction(()=>!document.querySelector('#show-exclusions').disabled);
    const response=page.waitForResponse(r=>r.url().includes('/api/excluded-roads?'));
    await page.check('#show-exclusions');
    const result=await response;assert.equal(result.status(),200);
    const data=await result.json();assert.ok(data.features.length>0);
    await page.waitForFunction(()=>window.__routeTestMap.queryRenderedFeatures({layers:['excluded-roads']}).length>0);
    const target=await page.evaluate(()=>{
      const map=window.__routeTestMap,rect=map.getCanvas().getBoundingClientRect();
      for(const f of map.queryRenderedFeatures({layers:['excluded-roads']})) {
        const lines=f.geometry.type==='LineString'?[f.geometry.coordinates]:f.geometry.coordinates;
        for(const line of lines)for(const c of line){const p=map.project(c);if(p.x>40&&p.y>40&&p.x<rect.width-40&&p.y<rect.height-40)return {x:p.x+rect.left,y:p.y+rect.top};}
      }
    });
    assert.ok(target);await page.mouse.click(target.x,target.y);
    await page.locator('.exclusion-detail').waitFor();
    assert.match(await page.locator('.exclusion-detail').innerText(),/Way \d+/);
    assert.equal(await page.locator('.maplibregl-marker').count(),0);
    await page.screenshot({path:'/tmp/excluded-roads-browser.png'});
    await page.uncheck('#show-exclusions');
    assert.equal(await page.locator('.exclusion-detail').count(),0);
    assert.equal(await page.evaluate(()=>window.__routeTestMap.getLayoutProperty('excluded-roads','visibility')),'none');
    // A response arriving after toggle-off must not repopulate the source.
    let release;const gate=new Promise(resolve=>{release=resolve;});let started;const seen=new Promise(resolve=>{started=resolve;});
    await page.route('**/api/excluded-roads?*',async route=>{const response=await route.fetch();started();await gate;await route.fulfill({response});});
    await page.check('#show-exclusions');await seen;await page.uncheck('#show-exclusions');release();
    await page.waitForTimeout(200);
    assert.equal(await page.evaluate(async()=>(await window.__routeTestMap.getSource('excluded-roads').getData()).features.length),0);
    assert.deepEqual(errors,[]);console.log('Excluded overlay PASS',data.features.length,'viewport roads');
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
