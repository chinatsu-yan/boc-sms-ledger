import asyncio,json
from pathlib import Path
from playwright.async_api import async_playwright
ROOT=Path(__file__).resolve().parent
async def run():
 async with async_playwright() as p:
  browser=await p.chromium.launch(executable_path='/usr/bin/chromium',args=['--no-sandbox','--disable-dev-shm-usage'])
  page=await browser.new_page(viewport={'width':1900,'height':1200},device_scale_factor=2)
  errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  await page.set_content((ROOT/'boc-ledger-design.html').read_text());await page.evaluate('document.fonts.ready')
  await page.locator('#design-board').screenshot(path=str(ROOT/'boc-ledger-design-overview.png'))
  metrics={}
  for i in ['overview','credit','transactions','settings']:
   el=page.locator('#'+i);await el.screenshot(path=str(ROOT/f'boc-ledger-{i}.png'))
   metrics[i]=await el.evaluate('''p=>{let m=p.querySelector('main').getBoundingClientRect(),n=p.querySelector('nav').getBoundingClientRect();return {contentBottom:m.bottom-p.getBoundingClientRect().top,navTop:n.top-p.getBoundingClientRect().top,horizontalOverflow:p.scrollWidth>p.clientWidth,overlap:m.bottom>n.top}}''')
  assert not errors,errors
  assert not any(m['overlap'] or m['horizontalOverflow'] for m in metrics.values()),metrics
  # Date configuration demonstrations are memory-only.
  assert await page.evaluate('designRules.updateDates().due')=='2026.07.12'
  await page.locator('[data-month="0"]').click();assert await page.evaluate('designRules.updateDates().invalid')
  assert await page.locator('#preview-save').is_disabled()
  assert await page.locator('#date-error').is_visible()
  assert await page.locator('#settings').evaluate("p=>p.querySelector('main').getBoundingClientRect().bottom<p.querySelector('nav').getBoundingClientRect().top")
  await page.locator('[data-month="1"]').click()
  assert await page.evaluate("designRules.clampedDate(2027,1,31).toISOString().slice(0,10)")=='2027-02-28'
  assert await page.evaluate("designRules.clampedDate(2028,1,31).toISOString().slice(0,10)")=='2028-02-29'
  await page.locator('[data-account="debit"]').click();assert await page.locator('#debit-list').is_visible()
  await page.locator('[data-account="credit"]').click();assert await page.locator('#credit-list').is_visible()
  await page.set_viewport_size({'width':430,'height':1000});assert await page.evaluate('document.documentElement.scrollWidth <= window.innerWidth')
  assert round(128.40*7.20,2)==924.48
  assert round(456.00+924.48,2)==1380.48
  assert round(sum([18,20.25,13.2,9,12,15,20,20.95]),2)==128.40
  result={'rendered_pixels_per_screen':[824,1864],'metrics':metrics,'browser_errors':errors,'checks':['next-month date','same-month conflict visible, blocks apply, no overlap','nonleap February clamp','leap February clamp','account toggle','430px viewport no page overflow']}
  (ROOT/'validation.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False))
  await browser.close()
asyncio.run(run())
