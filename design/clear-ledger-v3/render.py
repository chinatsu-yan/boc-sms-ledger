import asyncio,json
from pathlib import Path
from playwright.async_api import async_playwright
P=Path(__file__).parent
async def run():
 async with async_playwright() as p:
  browser=await p.chromium.launch(executable_path='/usr/bin/chromium',args=['--no-sandbox','--disable-dev-shm-usage'])
  page=await browser.new_page(viewport={'width':1390,'height':2000},device_scale_factor=2)
  errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  await page.set_content((P/'ledger-clear-v3.html').read_text());await page.evaluate('document.fonts.ready')
  checks={}
  for n in ['home','ledger','stats','credit','match','settings']:
   el=page.locator('#'+n)
   checks[n]=await el.evaluate('p=>({width:p.clientWidth,overflow:p.scrollWidth>p.clientWidth,contentHeight:p.querySelector("main").scrollHeight,viewportHeight:p.querySelector("main").clientHeight})')
   await el.screenshot(path=str(P/f'ledger-v3-{n}.png'))
  await page.locator('#board').screenshot(path=str(P/'ledger-v3-board.png'))
  await page.locator('#ledger .pending').click();await page.locator('[data-choice="餐饮"]').click();assert await page.locator('#ledger .row').nth(1).locator('.tag').inner_text()=='餐饮⌄'
  await page.locator('#batch').click();assert await page.locator('#batch-label').inner_text()=='已选 2 笔'
  await page.locator('#confirm-link').click();assert '已关联' in await page.locator('#confirm-link').inner_text();await page.locator('#confirm-link').click();assert '已解除' in await page.locator('#confirm-link').inner_text()
  await page.locator('#credit [data-info]').first.click();assert await page.locator('#modal').is_visible();await page.locator('#close').click()
  for w in [320,390,736]:
   await page.set_viewport_size({'width':w,'height':950})
   assert await page.evaluate('document.documentElement.scrollWidth<=innerWidth'),w
   for n in ['home','ledger','stats','credit','match','settings']:
    await page.locator('.proto-tabs [data-go="'+n+'"]').click()
    assert await page.locator('#'+n).evaluate('p=>p.scrollWidth<=p.clientWidth'),(w,n)
  assert not errors,errors
  (P/'validation.json').write_text(json.dumps({'screens':checks,'browser_errors':errors,'tests':['classification dialog','batch selection state','link and unlink state','information detail','320/390/736 responsive navigation and no horizontal overflow'],'chart_totals':{'categories':sum([900,600,360,300,240]),'weeks':sum([420,680,540,760]),'credit_net_usd':128.4-8.4,'reference_cny':120*7.2}},ensure_ascii=False,indent=2))
  print(checks)
  await browser.close()
asyncio.run(run())
