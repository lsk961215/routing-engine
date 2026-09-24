// Select fixed test coordinates through the same panel and map controls as a user.
exports.selectTestRoute = async page => {
  const start=[126.97721885,37.5690142],end=[127.0276,37.4981];
  for(const [kind,coordinate] of [['start',start],['end',end]]) {
    await page.click(`#select-${kind}`);
    await page.evaluate(c=>window.__routeTestMap.jumpTo({center:c,zoom:16}),coordinate);
    const point=await page.evaluate(c=>{
      const map=window.__routeTestMap,p=map.project(c),rect=map.getCanvas().getBoundingClientRect();
      return {x:p.x+rect.left,y:p.y+rect.top};
    },coordinate);
    await page.mouse.click(point.x,point.y);
  }
  await page.evaluate(([a,b])=>window.__routeTestMap.fitBounds([[a[0],b[1]],[b[0],a[1]]],{padding:90,duration:0}),[start,end]);
  await page.click('#search-route');
};
