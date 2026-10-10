'use strict';
window.Pet = (() => {
  const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const money = cents => (Number(cents || 0) / 100).toLocaleString('zh-CN', {maximumFractionDigits:2});
  const states = {UNPAID:'待支付',PENDING:'待店主确认',CONFIRMED:'预约已确认',ARRIVED:'已到店',IN_SERVICE:'服务中',COMPLETED:'已完成',CANCELLED:'已取消'};
  const categories = {DOG_BATH:'狗狗洗澡',CAT_BATH:'猫咪洗护',DOG_GROOM:'狗狗美容',CARE:'基础护理'};
  const dt = value => value ? String(value).replace('T',' ').slice(0,16) : '—';
  const date = offset => { const now = new Date(); now.setUTCDate(now.getUTCDate()+offset); return new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(now); };
  const parseTime = value => new Date(value && !/[Z]|[+-]\d\d:\d\d$/.test(value) ? value+'+08:00' : value);
  async function api(path, options={}, owner=false) {
    const token=sessionStorage.getItem(owner?'pet-owner-token':'pet-customer-token');
    const response=await fetch('/api/pet'+path,{...options,headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{}),...options.headers},body:options.body===undefined?undefined:JSON.stringify(options.body)});
    let result; try { result=await response.json(); } catch { throw new Error('服务暂时不可用，请稍后重试'); }
    if(!response.ok || !result.success) { if(response.status===401)sessionStorage.removeItem(owner?'pet-owner-token':'pet-customer-token'); const error=new Error(result.errorMsg || '操作未完成，请重试'); error.status=response.status; throw error; }
    return result.data;
  }
  let toastTimer;
  function toast(message) { const el=document.getElementById('toast'); el.textContent=message; el.classList.add('show'); clearTimeout(toastTimer); toastTimer=setTimeout(()=>el.classList.remove('show'),4000); }
  function modal(html) { document.getElementById('dialog-body').innerHTML=html; const dialog=document.getElementById('dialog'); if(!dialog.open) dialog.showModal(); }
  function close() { document.getElementById('dialog').close(); }
  document.querySelector('.dialog-close').addEventListener('click',close);
  document.getElementById('dialog').addEventListener('click',event=>{if(event.target===event.currentTarget)close();});
  function badge(status) { return `<span class="badge state-${escape(status)}">${escape(states[status]||status)}</span>`; }
  function countdown(deadline) { const left=Math.max(0,Math.ceil((parseTime(deadline)-Date.now())/1000)); return left?`${Math.floor(left/60)}:${String(left%60).padStart(2,'0')}`:'正在更新状态'; }
  function countdowns() { document.querySelectorAll('[data-deadline]').forEach(el=>{el.textContent=countdown(el.dataset.deadline);}); }
  setInterval(countdowns,1000);
  async function submit(button, task) { if(button.disabled)return; button.disabled=true; try { await task(); } catch(error) { toast(error.message); } finally { if(button.isConnected) button.disabled=false; } }
  const input = (label,name,value='',type='text',extra='') => `<label class="field"><span>${escape(label)}</span><input name="${escape(name)}" type="${type}" value="${escape(value)}" ${extra}></label>`;
  function formData(form) { return Object.fromEntries(new FormData(form).entries()); }
  return {escape,money,states,categories,dt,date,parseTime,api,toast,modal,close,badge,countdown,submit,input,formData};
})();
