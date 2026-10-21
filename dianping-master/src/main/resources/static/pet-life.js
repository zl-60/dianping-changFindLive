'use strict';
window.PetLife=(()=>{
  const {api,escape:e,money,dt,modal,close,toast,submit,input,formData}=Pet;
  let enabled=false,context,feedCursor,page=1,couponView=0;
  const ready=api('/capabilities').then(value=>{enabled=value.platform;document.querySelectorAll('[data-platform]').forEach(el=>el.hidden=!enabled);return enabled;});
  const logged=()=>Boolean(sessionStorage.getItem('pet-customer-token'));
  const need=task=>logged()?task():context.login(task);
  const shell=(title,description,content)=>`<div class="container life-page"><div class="page-title"><div class="eyebrow">PET COMPANION LIFE</div><h1>${title}</h1><p>${description}</p></div>${content}</div>`;
  const cards=posts=>posts.map(p=>`<article class="panel post-card"><button class="text-button post-author" data-profile="${e(p.authorId)}">○ ${e(p.nickname)}</button><h2>${e(p.title)}</h2><p class="post-content">${e(p.content)}</p>${p.placeName?`<a class="badge" href="#nearby">⌖ ${e(p.placeName)}</a>`:''}<div class="post-bottom"><small class="muted">${e(dt(p.createdAt))}</small><div><button class="text-button ${p.liked?'liked':''}" data-like="${e(p.id)}" data-liked="${p.liked}">${p.liked?'♥':'♡'} ${e(p.likes)}</button><button class="text-button" data-likers="${e(p.id)}">点赞的宠友</button></div></div></article>`).join('');
  function bindPosts(root=context.app){
    root.querySelectorAll('[data-profile]').forEach(button=>button.onclick=()=>profile(button.dataset.profile));
    root.querySelectorAll('[data-like]').forEach(button=>button.onclick=()=>need(()=>submit(button,async()=>{
      const p=await api(`/community/posts/${button.dataset.like}/like`,{method:'PUT',body:{liked:button.dataset.liked!=='true'}});
      button.dataset.liked=String(p.liked);button.textContent=`${p.liked?'♥':'♡'} ${p.likes}`;button.classList.toggle('liked',p.liked);
    })));
    root.querySelectorAll('[data-likers]').forEach(button=>button.onclick=()=>submit(button,async()=>{
      const users=await api(`/community/posts/${button.dataset.likers}/likers`);
      modal(`<div class="dialog-heading"><h2>最近点赞的宠友</h2><p>按点赞时间展示最近五位宠友。</p></div>${users.map(u=>`<p>${e(u.nickname)}</p>`).join('')||'<p class="muted">还没有人点赞，成为第一位吧。</p>'}`);
    }));
  }
  async function profile(id){
    try {
      const p=await api(`/community/members/${encodeURIComponent(id)}/profile`);
      modal(`<div class="dialog-heading"><h2>${e(p.nickname)}</h2><p>${e(p.followers)} 位粉丝 · 关注 ${e(p.following)} 位宠友</p></div><button class="btn full" id="follow-member">${p.isFollowing?'取消关注':'关注这位宠友'}</button><button class="btn outline full" id="common-members" style="margin-top:12px">查看共同关注</button><div id="common-result"></div>`);
      document.getElementById('follow-member').onclick=function(){need(()=>submit(this,async()=>{await api(`/community/members/${id}/follow`,{method:'PUT',body:{following:!p.isFollowing}});toast(p.isFollowing?'已取消关注':'关注成功，可在关注动态查看分享');await profile(id);}));};
      document.getElementById('common-members').onclick=function(){need(()=>submit(this,async()=>{const users=await api(`/community/members/${id}/common`);document.getElementById('common-result').textContent=users.length?users.map(u=>u.nickname).join('、'):'暂时没有共同关注的宠友';}));};
    }catch(error){toast(error.message);}
  }
  async function nearby(){
    context.app.innerHTML=shell('身边的宠物好去处','发现附近门店，为下一次陪伴选个好地方。',`<form class="panel location-form" id="location-form"><div class="form-grid">${input('经度','longitude',120.155070,'number','required min="-180" max="180" step="any"')}${input('纬度','latitude',30.274084,'number','required min="-85" max="85" step="any"')}${input('附近范围（公里）','radius',5,'number','required min="0.1" max="50" step="0.1"')}</div><div class="form-actions"><button type="button" class="btn outline" id="locate">使用我的位置</button><button class="btn">查找门店</button></div><p class="muted small-text" id="location-hint">当前为杭州示例坐标，可定位或自行输入坐标。</p></form><div class="life-grid" id="places-list"></div>`);
    const form=document.getElementById('location-form');
    async function search(){
      const places=await api('/places?'+new URLSearchParams(formData(form)));
      document.getElementById('places-list').innerHTML=places.map(p=>`<article class="panel place-card"><span class="badge">${e(p.category)}</span><h2>${e(p.name)}</h2><p>${e(p.description)}</p><p class="muted">⌖ ${e(p.address)}<br>距你约 ${e(p.distanceKm)} 公里</p><button class="btn outline" data-place="${e(p.id)}">查看门店</button></article>`).join('')||'<div class="empty">这个范围内暂时没有门店，试试扩大范围。</div>';
      document.querySelectorAll('[data-place]').forEach(button=>button.onclick=()=>submit(button,async()=>{
        const p=await api('/places/'+button.dataset.place);
        modal(`<div class="dialog-heading"><p class="eyebrow">${e(p.category)}</p><h2>${e(p.name)}</h2><p>${e(p.address)}</p></div><p>${e(p.description)}</p>${Number(p.bookable)===1?'<a class="btn full" id="place-book" href="#services">选择洗护服务</a>':'<p class="notice">这家门店目前提供信息展示，尚未开放在线预约。</p>'}`);
        const link=document.getElementById('place-book');if(link)link.onclick=close;
      }));
    }
    form.onsubmit=event=>{event.preventDefault();submit(event.submitter,search);};
    document.getElementById('locate').onclick=function(){
      if(!navigator.geolocation){toast('浏览器暂不支持定位，请输入坐标');return;}
      this.disabled=true;
      navigator.geolocation.getCurrentPosition(position=>{this.disabled=false;form.elements.longitude.value=position.coords.longitude;form.elements.latitude.value=position.coords.latitude;document.getElementById('location-hint').textContent='已使用你的当前位置。';search().catch(error=>toast(error.message));},()=>{this.disabled=false;toast('未能获取位置，你可以输入坐标查询');},{timeout:10000});
    };
    await search();
  }
  async function coupons(){
    const view=++couponView;
    const [list,mine]=await Promise.all([api('/coupons'),logged()?api('/my-coupons'):Promise.resolve([])]);
    if(!currentCouponView(view))return;
    context.app.innerHTML=shell('给毛孩子的限量好券','先领一份小心意，预约洗护时可按条件抵扣。',`<div id="claim-status" role="status" aria-atomic="true" hidden></div><section class="life-grid">${list.map(c=>`<article class="panel coupon-card"><div class="eyebrow">${e(c.placeName)}</div><h2>${e(c.title)}</h2><div class="coupon-value">¥${money(c.discountCents)} <small>满 ¥${money(c.minSpendCents)} 可用</small></div><p class="muted">活动：${e(dt(c.startsAt))} — ${e(dt(c.endsAt))}<br>使用有效期至 ${e(dt(c.validUntil))}<br>剩余 ${e(c.stock)} 张 · 每人限领一张</p><button class="btn full" data-claim="${e(c.id)}">立即抢券</button><p class="coupon-feedback" data-claim-feedback="${e(c.id)}" role="status" aria-atomic="true" hidden></p></article>`).join('')||'<div class="empty">暂时没有抢券活动。</div>'}</section><section class="panel"><div class="table-toolbar"><h2>我的优惠券</h2><button class="btn small outline" id="refresh-coupons">刷新</button></div><div id="coupon-wallet">${wallet(mine)}</div></section>`);
    document.getElementById('refresh-coupons').onclick=()=>context.render();
    const login=document.getElementById('coupon-login');if(login)login.onclick=()=>context.login(context.render);
    const buttons=[...document.querySelectorAll('[data-claim]')];
    let busy=false;
    async function claim(button,saved){
      if(busy||!currentCouponView(view))return;
      busy=true;
      const key='pet-pending-coupon:'+sessionStorage.getItem('pet-customer-phone');
      let pending=saved||pendingCoupon(key),couponId=pending?.couponId||button?.dataset.claim;
      buttons.forEach(item=>item.disabled=true);
      if(button){button.textContent=pending?'确认领取中…':'正在抢券…';button.setAttribute('aria-busy','true');}
      couponNotice(view,couponId,pending?'正在确认上次的领取结果，请稍候…':'正在为你抢券，请稍候…','pending');
      try{
        if(!pending){
          const result=await api(`/coupons/${couponId}/claim`,{method:'POST',body:{}});
          if(result.alreadyClaimed===true){
            couponNotice(view,couponId,result.message||'你已经领取过该优惠券，每人限领一次。可在“我的优惠券”查看。','error');
            return;
          }
          pending={orderId:result.orderId,couponId};
          localStorage.setItem(key,JSON.stringify(pending));
        }
        await poll(pending.orderId,key,view,couponId);
      }catch(error){
        const reason=error.status===401?'登录已过期，请重新登录后再试':error instanceof TypeError?'网络连接不稳定，请检查网络后重试':error.message||'服务暂时繁忙，请稍后再试';
        couponNotice(view,couponId,pending?`领取结果暂未确认：${reason}。申请已保留，可稍后刷新查看，请勿重复抢券。`:`领取未成功：${reason}。`,'error');
      }finally{
        busy=false;
        buttons.forEach(item=>{if(item.isConnected)item.disabled=false;});
        if(button?.isConnected){button.textContent='立即抢券';button.removeAttribute('aria-busy');}
      }
    }
    buttons.forEach(button=>button.onclick=()=>need(()=>claim(button)));
    if(logged()){
      const saved=pendingCoupon('pet-pending-coupon:'+sessionStorage.getItem('pet-customer-phone'));
      if(saved)await claim(buttons.find(button=>button.dataset.claim===String(saved.couponId)),saved);
    }
  }
  const currentCouponView=view=>view===couponView&&location.hash==='#coupons';
  function pendingCoupon(key){
    const value=localStorage.getItem(key);
    if(!value)return null;
    // Earlier versions stored only the order ID; preserve it as a string.
    if(!value.startsWith('{'))return {orderId:value};
    try{return JSON.parse(value);}catch{localStorage.removeItem(key);return null;}
  }
  function wallet(coupons){
    if(!logged())return '<button class="btn outline" id="coupon-login">登录后查看</button>';
    return coupons.length?coupons.map(c=>`<div class="wallet-row"><div><strong>${e(c.title)}</strong><p class="muted">满 ¥${money(c.minSpendCents)} 减 ¥${money(c.discountCents)} · ${c.status==='USED'?'已用于预约':c.status==='FAILED'?'领取失败':new Date(c.validUntil+'+08:00')<new Date()?'已过期':'待使用'}</p></div>${c.status==='SUCCESS'?'<a class="btn small outline" href="#services">去预约</a>':''}</div>`).join(''):'<p class="muted">还没有优惠券，挑一张喜欢的领取吧。</p>';
  }
  function couponNotice(view,couponId,message,kind,notify=true){
    if(!currentCouponView(view))return;
    const box=document.getElementById('claim-status');
    if(box){box.hidden=false;box.className='notice coupon-feedback-'+kind;box.textContent=message;}
    const card=[...document.querySelectorAll('[data-claim-feedback]')].find(item=>item.dataset.claimFeedback===String(couponId));
    if(card){card.hidden=false;card.className='coupon-feedback coupon-feedback-'+kind;card.textContent=message;}
    if(notify)toast(message);
  }
  async function poll(id,key,view,couponId){
    for(let attempt=0;attempt<=20;attempt++){
      if(!currentCouponView(view))return;
      const result=await api('/coupon-orders/'+encodeURIComponent(id));
      if(!currentCouponView(view))return;
      if(result.state==='PENDING'){
        couponNotice(view,couponId,'抢券请求已受理，正在确认领取结果，请稍候…','pending',false);
        if(attempt<20){await new Promise(resolve=>setTimeout(resolve,1000));continue;}
      }else if(['SUCCESS','USED','FAILED'].includes(result.state)){
        localStorage.removeItem(key);
        const failed=result.state==='FAILED';
        couponNotice(view,couponId,failed?`这次没抢到：${result.reason||'优惠券已领完'}。看看其他好券吧。`:result.state==='USED'?'这张优惠券已用于预约，可在“我的预约”查看。':'领取成功！优惠券已放入“我的优惠券”，预约洗护时就能使用。',failed?'error':'success');
        if(!failed){
          try{
            const coupons=await api('/my-coupons');
            if(currentCouponView(view))document.getElementById('coupon-wallet').innerHTML=wallet(coupons);
          }catch{
            couponNotice(view,couponId,'领取成功！券包暂未刷新，请稍后点击“我的优惠券”旁的刷新查看。','success');
          }
        }
        return;
      }
      couponNotice(view,couponId,'领取结果仍在确认中，申请已保留。请稍后刷新“我的优惠券”查看，无需重复抢券。','pending');
      return;
    }
  }
  async function compose(){
    const places=await api('/places');
    modal(`<div class="dialog-heading"><h2>分享一段养宠日常</h2><p>探店、护理心得，或毛孩子的小事。</p></div><form id="post-form">${input('标题','title','','text','required maxlength="80"')}<label class="field"><span>正文</span><textarea name="content" required maxlength="2000" rows="6"></textarea></label><label class="field"><span>关联门店（选填）</span><select name="placeId"><option value="">日常分享</option>${places.map(p=>`<option value="${e(p.id)}">${e(p.name)}</option>`).join('')}</select></label><button class="btn full">发布动态</button></form>`);
    document.getElementById('post-form').onsubmit=event=>{event.preventDefault();submit(event.submitter,async()=>{await api('/community/posts',{method:'POST',body:formData(event.target)});close();toast('动态已发布');location.hash='community';await context.render();});};
  }
  async function community(route){
    if(route==='feed'&&!logged()){context.app.innerHTML=shell('关注动态','关注喜欢的宠友，让有趣的日常来到你面前。','<button class="btn" id="feed-login">登录查看关注动态</button>');document.getElementById('feed-login').onclick=()=>context.login(context.render);return;}
    page=1;feedCursor=null;
    const hot=route==='hot',feed=route==='feed';
    const result=await api(feed?'/community/feed':hot?'/community/hot':'/community/posts');
    if(feed)feedCursor=result;
    context.app.innerHTML=shell('一起过有毛孩子的生活','分享日常、发现同好，也记录每一次用心照顾。',`<div class="community-toolbar"><div class="chips"><a class="chip ${!hot&&!feed?'active':''}" href="#community">最新分享</a><a class="chip ${hot?'active':''}" href="#hot">人气动态</a><a class="chip ${feed?'active':''}" href="#feed">关注动态</a></div><div><button class="btn small outline" id="sign-entry">签到 / 我的资料</button><button class="btn small" id="compose-post">＋ 发动态</button></div></div><section id="post-list">${cards(feed?result.items:result)||'<div class="empty">这里还很安静，分享你的第一段养宠日常吧。</div>'}</section>${!hot?'<button class="btn outline full" id="more-posts">查看更多</button>':''}`);
    document.getElementById('compose-post').onclick=()=>need(()=>compose().catch(error=>toast(error.message)));
    document.getElementById('sign-entry').onclick=()=>need(()=>account().catch(error=>toast(error.message)));
    bindPosts();const more=document.getElementById('more-posts');
    if(more){more.hidden=feed?!result.hasMore:result.length<10;more.onclick=()=>submit(more,async()=>{
      const next=await api(feed?`/community/feed?max=${encodeURIComponent(feedCursor.max)}&offset=${feedCursor.offset}`:`/community/posts?page=${++page}`);
      document.getElementById('post-list').insertAdjacentHTML('beforeend',cards(feed?next.items:next));
      if(feed)feedCursor=next;more.hidden=feed?!next.hasMore:next.length<10;bindPosts();
    });}
  }
  async function account(){
    const [me,sign]=await Promise.all([api('/community/me'),api('/sign')]);
    modal(`<div class="dialog-heading"><h2>${e(me.nickname)}</h2><p>${e(me.followers)} 位粉丝 · 关注 ${e(me.following)} 位宠友</p></div><div class="notice">${e(sign.month)} 已签到 ${sign.monthCount} 天，本月连续签到 ${sign.continuousDaysInMonth} 天。</div><button class="btn full" id="daily-sign" ${sign.signedToday?'disabled':''}>${sign.signedToday?'今天已签到':'今日签到'}</button><form id="nickname-form" style="margin-top:20px">${input('社区昵称','nickname',me.nickname,'text','required maxlength="40"')}<button class="btn outline full">保存昵称</button></form>`);
    document.getElementById('daily-sign').onclick=function(){submit(this,async()=>{await api('/sign',{method:'POST',body:{}});toast('签到成功');await account();});};
    document.getElementById('nickname-form').onsubmit=event=>{event.preventDefault();submit(event.submitter,async()=>{await api('/community/me',{method:'PUT',body:formData(event.target)});toast('昵称已保存');await account();});};
  }
  async function bookingCoupon(form,price,draft,login,render){
    if(!enabled)return;
    const holder=document.createElement('div');holder.className='booking-coupon';
    form.querySelector('.form-actions').before(holder);
    if(!logged()){holder.innerHTML='<button type="button" class="text-button" id="login-for-coupon">登录后可选择优惠券</button>';holder.firstChild.onclick=()=>login(render);return;}
    const coupons=await api('/my-coupons');
    const usable=coupons.filter(c=>c.status==='SUCCESS'&&Number(c.minSpendCents)<=price&&new Date(c.validUntil+'+08:00')>new Date());
    holder.innerHTML=`<label class="field"><span>预约优惠券</span><select name="couponOrderId"><option value="">不使用优惠券</option>${usable.map(c=>`<option value="${e(c.id)}" ${String(c.id)===String(draft.couponOrderId)?'selected':''}>${e(c.title)} · 减 ¥${money(Math.min(price,c.discountCents))}</option>`).join('')}</select></label><p class="muted small-text">下单时锁定优惠券；预约取消后退回，有效期保持原样。</p>`;
  }
  return {ready,get enabled(){return enabled;},bookingCoupon,async render(route,options){context=options;if(route!=='coupons')couponView++;if(!enabled)return false;if(route==='nearby')await nearby();else if(route==='coupons')await coupons();else if(['community','feed','hot'].includes(route))await community(route);else return false;return true;}};
})();
