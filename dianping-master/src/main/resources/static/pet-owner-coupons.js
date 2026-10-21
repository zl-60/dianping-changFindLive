'use strict';
window.renderPetCoupons=async function(el,refresh){
  const {escape:e,dt,money,modal,close,toast,submit,input,formData}=Pet;
  const api=(path,options)=>Pet.api(path,options,true);
  const coupons=await api('/owner/coupons');
  el.innerHTML=`<section class="panel"><div class="table-toolbar"><h2>限量洗护优惠券</h2><button class="btn small" id="add-coupon">＋ 新建活动</button></div><p class="muted">优惠券适用于本店在线预约。发布后活动规则固定，每人限领一张。</p><div class="manage-grid">${coupons.map(c=>`<article class="manage-card"><span class="badge">${Number(c.published)===1?'已发布':'草稿'}</span><h3>${e(c.title)}</h3><p>满 ¥${money(c.minSpendCents)} 减 ¥${money(c.discountCents)}<br>剩余库存 ${e(c.stock)} 张</p><p class="muted small-text">活动 ${e(dt(c.startsAt))} — ${e(dt(c.endsAt))}<br>使用有效期至 ${e(dt(c.validUntil))}</p><button class="btn small outline" data-publish="${e(c.id)}" ${Number(c.published)===1?'disabled':''}>${Number(c.published)===1?'已发布':'发布活动'}</button></article>`).join('')}</div></section>`;
  el.querySelectorAll('[data-publish]').forEach(button=>button.onclick=()=>submit(button,async()=>{await api(`/owner/coupons/${button.dataset.publish}/publish`,{method:'POST',body:{}});toast('活动已发布');await refresh();}));
  document.getElementById('add-coupon').onclick=()=>{
    const local=d=>new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(d).replace(' ','T');
    modal(`<div class="dialog-heading"><h2>新建优惠券活动</h2><p>发布前核对金额、库存和活动时间。</p></div><form id="coupon-form">${input('活动名称','title','','text','required maxlength="80"')}<div class="form-grid">${input('优惠金额（元）','discount',20,'number','required min="0.01" max="10000" step="0.01"')}${input('最低消费（元）','minimum',69,'number','required min="0" max="10000" step="0.01"')}${input('库存（张）','stock',30,'number','required min="1" max="100000" step="1"')}${input('开始时间','startsAt',local(new Date()),'datetime-local','required')}${input('结束时间','endsAt',local(new Date(Date.now()+7*86400000)),'datetime-local','required')}${input('使用有效期至','validUntil',local(new Date(Date.now()+30*86400000)),'datetime-local','required')}</div><button class="btn full">保存为草稿</button></form>`);
    document.getElementById('coupon-form').onsubmit=event=>{event.preventDefault();submit(event.submitter,async()=>{const values=formData(event.target);await api('/owner/coupons',{method:'POST',body:{...values,discountCents:Math.round(Number(values.discount)*100),minSpendCents:Math.round(Number(values.minimum)*100)}});close();toast('草稿已保存，发布后顾客即可抢券');await refresh();});};
  };
};
