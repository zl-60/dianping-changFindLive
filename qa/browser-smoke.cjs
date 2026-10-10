const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const path=require('node:path');

(async()=>{
  const base=process.env.PET_BASE_URL||'http://127.0.0.1:8081';
  assert.ok(['127.0.0.1','localhost'].includes(new URL(base).hostname),'This smoke test is for the local demo.');
  const output=path.resolve(__dirname,'../.local-backups/browser-results');await fs.mkdir(output,{recursive:true});
  const browser=process.env.PET_CHROME_CDP
    ? await chromium.connectOverCDP(process.env.PET_CHROME_CDP)
    : await chromium.launch({headless:true,executablePath:process.env.PET_CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
  const errors=[];let owner,orderId;
  try {
    const mobileContext=await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true});
    const desktopContext=await browser.newContext({viewport:{width:1440,height:1000}});
    const customer=await mobileContext.newPage();owner=await desktopContext.newPage();
    customer.on('pageerror',error=>errors.push(error.message));owner.on('pageerror',error=>errors.push(error.message));
    await customer.goto(base);await customer.waitForSelector('.service-card');
    assert.equal(await customer.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    await customer.screenshot({path:path.join(output,'customer-home.png'),fullPage:true});
    await customer.locator('.service-card[href="#service/DOG_BATH"]').click();
    await customer.locator('#book-link').click();await customer.waitForSelector('.date-button');
    assert.equal(await customer.locator('[name="petName"]').count(),0,'Pet fields must not appear before choosing time.');
    await customer.locator('.date-button').nth(1).click();
    await customer.waitForFunction(()=>document.querySelector('.date-button.active')?.dataset.date!==new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date()));
    await customer.locator('.slot:not(:disabled)').first().click();
    const time=await customer.locator('.slot.selected').getAttribute('data-time');assert.equal(time.slice(14,16),'00');
    await customer.screenshot({path:path.join(output,'choose-time.png'),fullPage:true});
    await customer.locator('#next-step').click();await customer.waitForSelector('#booking-form');
    const phone='138'+String(Date.now()).slice(-8);
    await customer.locator('[name="petName"]').fill('浏览器验收豆豆');await customer.locator('[name="breed"]').fill('比熊');
    await customer.locator('[name="weight"]').fill('5');await customer.locator('[name="contactName"]').fill('演示家长');
    await customer.locator('[name="contactPhone"]').fill(phone);await customer.locator('[name="notes"]').fill('怕吹风，请温柔照顾');
    await customer.locator('#booking-form button[type="submit"]').click();await customer.waitForSelector('#login-form');
    await customer.locator('#login-form [name="phone"]').fill(phone);await customer.locator('#get-code').click();
    await customer.waitForFunction(()=>document.querySelector('#login-form [name="code"]')?.value.length===6);
    await customer.locator('#login-form button[type="submit"]').click();await customer.waitForURL(/#order\//);
    await customer.waitForSelector('#pay-order');orderId=(new URL(customer.url())).hash.split('/')[1];
    assert.ok((await customer.locator('#app').innerText()).includes('浏览器验收豆豆'),'Login must preserve booking form.');
    await customer.locator('#pay-order').click();await customer.locator('#confirm-pay').click();await customer.waitForSelector('.state-PENDING');
    await owner.goto(base+'/owner.html');await owner.locator('[name="username"]').fill(process.env.PET_ADMIN_USERNAME||'petadmin');
    await owner.locator('[name="password"]').fill(process.env.PET_ADMIN_PASSWORD||'PetDemo2026!');
    await owner.locator('#owner-login button').click();await owner.waitForSelector('.owner-shell');
    await owner.screenshot({path:path.join(output,'owner-dashboard.png'),fullPage:true});
    await owner.locator(`[data-order="${orderId}"]`).click();await owner.locator('[data-action="confirm"]').click();
    await owner.waitForFunction(()=>!document.querySelector('#dialog').open);
    await customer.reload();await customer.waitForSelector('.booking-code');assert.equal(await customer.locator('.state-CONFIRMED').count(),1);
    await customer.screenshot({path:path.join(output,'confirmed-order.png'),fullPage:true});
    assert.equal(await customer.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    for(const [tab,selector] of Object.entries({services:'#add-spec',staff:'#add-staff',shifts:'#add-shift',store:'#store-form'})) {
      await owner.goto(base+'/owner.html#'+tab);await owner.waitForSelector(selector);
    }
    await customer.locator('#cancel-order').click();await customer.locator('#confirm-cancel').click();await customer.waitForSelector('.state-CANCELLED');
    assert.ok((await customer.locator('#app').innerText()).includes('已全额模拟退款'));
    const other=await desktopContext.newPage();await other.goto(base+'/');
    const denied=await other.evaluate(async()=>{const response=await fetch('/api/pet/owner/orders');return response.status;});assert.equal(denied,401);
    assert.deepEqual(errors,[]);
    const result={passed:true,checks:['mobile layout','time before pet fields','hourly slots','form preserved through login','booking and payment','owner confirmation','booking code','management pages','cancel and refund','unauthenticated owner access'],orderId,consoleErrors:errors};
    await fs.writeFile(path.join(output,'result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
  } finally {
    if(owner&&orderId)try{await owner.evaluate(async id=>{const token=sessionStorage.getItem('pet-owner-token');if(token)await fetch('/api/pet/owner/orders/'+id+'/cancel',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:JSON.stringify({reason:'浏览器验收结束'})});},orderId);}catch{}
    await browser.close();
  }
})().catch(error=>{console.error(error);process.exitCode=1;});
