const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const path=require('node:path');

// Exercise the real customer page with deterministic API replies, without consuming live coupons.
(async()=>{
  const root=path.resolve(__dirname,'../dianping-master/src/main/resources/static');
  const assetBase=process.env.PET_COUPON_ASSET_BASE;
  if(assetBase)assert.ok(['127.0.0.1','localhost'].includes(new URL(assetBase).hostname),'Live assets must come from the local preview.');
  const output=path.resolve(__dirname,'../.local-backups/browser-coupon-feedback');
  await fs.mkdir(output,{recursive:true});
  const browser=await chromium.launch({headless:true,executablePath:process.env.PET_CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
  const checks=[];
  try{
    for(const scenario of process.argv.length>2?process.argv.slice(2):['success','rejected','async-failure','network','pending','expired-login','poll-network','pending-timeout','unknown-state','wallet-refresh','duplicate','duplicate-existing','duplicate-used','duplicate-pending']){
      const context=await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true});
      const page=await context.newPage(),errors=[];
      page.on('pageerror',error=>errors.push(error.message));
      let polls=0,claims=0;
      await context.addInitScript(()=>{sessionStorage.setItem('pet-customer-token','test-token');sessionStorage.setItem('pet-customer-phone','13900000000');});
      if(scenario==='pending-timeout')await context.addInitScript(()=>{const delay=window.setTimeout;window.setTimeout=(fn,ms,...args)=>delay(fn,ms===1000?0:ms,...args);});
      await page.route('http://127.0.0.1:18082/**',async route=>{
        const url=new URL(route.request().url()),endpoint=url.pathname.replace('/api/pet','');
        const json=(data,status=200)=>route.fulfill({status,json:{success:status===200,data,errorMsg:status===200?undefined:data}});
        if(url.pathname.startsWith('/api/pet')){
          if(endpoint==='/capabilities')return json({platform:true});
          if(endpoint==='/store')return json({name:'测试门店'});
          if(endpoint==='/services')return json([]);
          if(endpoint==='/coupons')return json([1,2,3].map(id=>({id,title:'限量洗护券 '+id,placeName:'宠伴门店',discountCents:2000,minSpendCents:6900,stock:10,startsAt:'2026-01-01T00:00:00',endsAt:'2027-01-01T00:00:00',validUntil:'2027-02-01T00:00:00'})));
          if(endpoint==='/my-coupons'){
            if(scenario==='wallet-refresh'&&polls)return json('券包暂时不可用',503);
            return json(scenario==='success'&&polls?[{id:'9001',title:'限量洗护券 1',status:'SUCCESS',discountCents:2000,minSpendCents:6900,validUntil:'2027-02-01T00:00:00'}]:[]);
          }
          if(endpoint==='/coupons/1/claim'){
            claims++;
            if(scenario==='rejected')return json('优惠券已领完',409);
            if(scenario==='expired-login')return json('请先登录',401);
            if(scenario==='network')return route.abort('failed');
            if(scenario==='duplicate-existing'||scenario==='duplicate-used'||scenario==='duplicate'&&claims>1)return json({orderId:'9001',state:scenario==='duplicate-used'?'USED':'SUCCESS',alreadyClaimed:true,message:'你已经领取过该优惠券，每人限领一次。可在“我的优惠券”查看。'});
            if(scenario==='duplicate-pending')return json({orderId:'9001',state:'PENDING',alreadyClaimed:false,message:'该优惠券正在领取中，请稍候查看结果'});
            return json({orderId:'9001'});
          }
          if(endpoint==='/coupon-orders/9001'){
            polls++;
            if(scenario==='poll-network'&&polls===1)return route.abort('failed');
            if(scenario==='pending')await new Promise(resolve=>setTimeout(resolve,200));
            return json({state:scenario==='async-failure'?'FAILED':scenario==='unknown-state'?'UNKNOWN':scenario==='pending-timeout'||['pending','duplicate-pending'].includes(scenario)&&polls<2?'PENDING':'SUCCESS',reason:scenario==='async-failure'?'活动已结束':undefined});
          }
          throw new Error('Unexpected API: '+endpoint);
        }
        const file=url.pathname==='/'?'index.html':url.pathname.slice(1);
        if(!['index.html','pet.css','pet-common.js','pet-life.js','pet.js'].includes(file))return route.fulfill({status:404,body:''});
        let body;
        if(assetBase){
          const response=await context.request.get(new URL(file,assetBase).href);
          assert.ok(response.ok(),'Preview asset is unavailable: '+file);
          body=await response.body();
        }else body=await fs.readFile(path.join(root,file));
        return route.fulfill({body,contentType:file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'text/html'});
      });
      await page.goto('http://127.0.0.1:18082/#coupons');
      const button=page.locator('[data-claim="1"]');
      await button.click();
      if(scenario==='duplicate'){
        await page.waitForFunction(()=>document.querySelector('#toast.show')?.textContent.includes('领取成功'));
        await page.waitForFunction(()=>!document.querySelector('[data-claim="1"]').disabled);
        await button.click();
      }
      if(scenario==='pending'){
        await page.waitForFunction(()=>document.querySelector('#toast.show')?.textContent.includes('正在'));
        assert.equal(await button.isDisabled(),true,'Pending claims must prevent duplicate clicks');
      }
      const expected={success:'领取成功',rejected:'已领完','async-failure':'活动已结束',network:'网络',pending:'领取成功','expired-login':'登录已过期','poll-network':'领取结果暂未确认','pending-timeout':'仍在确认','unknown-state':'仍在确认','wallet-refresh':'券包暂未刷新',duplicate:'已经领取过','duplicate-existing':'已经领取过','duplicate-used':'已经领取过','duplicate-pending':'领取成功'}[scenario];
      await page.waitForFunction(text=>document.querySelector('#toast.show')?.textContent.includes(text),expected,{timeout:4000}).catch(async()=>{
        const actual=await page.evaluate(()=>({toast:document.querySelector('#toast')?.textContent,status:document.querySelector('#claim-status')?.textContent,statusTop:document.querySelector('#claim-status')?.getBoundingClientRect().top,viewport:innerHeight}));
        throw new Error(`${scenario}: missing visible result notification (${expected}); ${JSON.stringify(actual)}`);
      });
      assert.equal(claims,scenario==='duplicate'?2:1);
      await page.waitForFunction(()=>!document.querySelector('[data-claim="1"]').disabled);
      await page.waitForFunction(()=>getComputedStyle(document.querySelector('#toast')).opacity==='1');
      const notification=await page.locator('#toast').boundingBox();
      assert.ok(notification.y>=0&&notification.y+notification.height<=844,'Result notification must fit inside the mobile viewport');
      assert.ok((await page.locator('[data-claim-feedback="1"]').innerText()).includes(expected),'The clicked coupon must retain its result');
      if(scenario==='success')assert.equal(await page.locator('.wallet-row').count(),1,'Successful claims should refresh the wallet automatically');
      if(scenario==='expired-login')assert.equal(await page.evaluate(()=>sessionStorage.getItem('pet-customer-token')),null);
      if(['duplicate','duplicate-existing','duplicate-used'].includes(scenario)){
        assert.equal(await page.evaluate(()=>localStorage.getItem('pet-pending-coupon:13900000000')),null,'Already received coupons must not create a new pending request');
        assert.equal(polls,scenario==='duplicate'?1:0,'Known duplicate claims must not be polled as new claims');
      }
      if(['poll-network','pending-timeout','unknown-state'].includes(scenario))assert.ok(await page.evaluate(()=>localStorage.getItem('pet-pending-coupon:13900000000')),'Unconfirmed claims must remain recoverable');
      if(scenario==='poll-network'){
        await button.click();
        await page.waitForFunction(()=>document.querySelector('#toast.show')?.textContent.includes('领取成功'));
        assert.equal(claims,1,'Retrying confirmation must not submit another claim');
      }
      if(['success','async-failure','network','duplicate'].includes(scenario))await page.screenshot({path:path.join(output,scenario+'.png')});
      assert.deepEqual(errors,[]);
      checks.push(scenario);
      await context.close();
    }
    console.log(JSON.stringify({passed:true,checks}));
  }finally{await browser.close();}
})().catch(error=>{console.error(error.message);process.exitCode=1;});
