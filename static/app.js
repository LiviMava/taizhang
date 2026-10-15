(() => {
  'use strict';
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const titles = {'ledgers':'系统集成台账','tc-systems':'TC系统维护','divisions':'事业部管理','partner-systems':'集成方系统管理'};
  const names = {'tc-systems':'TC系统名称','divisions':'事业部名称','partner-systems':'集成方系统名称'};
  const state = {resource:'ledgers',page:1,size:20,total:0,filters:{},rows:new Map(),refs:null,sequence:0,editor:null,saving:false};
  let toastTimer, requestController, pendingDelete;

  function connected(ok) {
    $('#connection-dot').className = `connection-dot ${ok?'connected':'disconnected'}`;
    $('#connection-label').textContent = ok ? '服务已连接' : '连接异常，请刷新';
  }
  async function api(path, options = {}) {
    let response;
    try {
      response = await fetch(path, {cache:'no-store',...options,
        signal:options.signal || AbortSignal.timeout(15000),
        headers:{'Accept':'application/json',...(options.body?{'Content-Type':'application/json','X-Actor':'local-ui'}:{}),...options.headers}});
    } catch(error) {
      if (error.name === 'AbortError') throw error;
      connected(false);
      throw new Error('连接服务失败，请确认项目已启动后重试。');
    }
    if (!response.ok) {
      const data = await response.json().catch(() => ({}));
      const error = new Error(data.error?.message || `请求失败（${response.status}）`);
      error.status = response.status;
      if (data.error?.details?.length) error.message += '\n' + data.error.details.map(d => `${d.field}：${d.message}`).join('\n');
      throw error;
    }
    connected(true);
    if (options.blob) return response.blob();
    return response.status === 204 ? null : response.json();
  }
  function notify(message, error = false) {
    clearTimeout(toastTimer);
    const node = $('#toast'); node.textContent = message; node.className = `toast${error?' error':''}`; node.hidden = false;
    toastTimer = setTimeout(() => {node.hidden = true;}, error ? 6000 : 3000);
  }
  function errorBox(selector, message) { const node=$(selector);node.textContent=message||'';node.hidden=!message; }
  const date = value => value ? new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date(value)) : '—';
  const badge = (status, base = false) => `<span class="badge ${status==='active'||status==='enabled'?'active':'invalid'}">${base?(status==='enabled'?'启用':'禁用'):(status==='active'?'生效':'作废')}</span>`;
  const providers = [['tc','TC提供'],['non_tc','非TC提供']];
  const statuses = [['active','生效'],['invalid','作废']];
  const baseStatuses = [['enabled','启用'],['disabled','禁用']];
  function select(label, name, options, value = '', {required = false, empty = '全部', full = false} = {}) {
    return `<label class="field${full?' full':''}"><span>${esc(label)}${required?' <b class="required">*</b>':''}</span><select name="${esc(name)}" ${required?'required':''}>${empty!==null?`<option value="">${esc(empty)}</option>`:''}${options.map(([id,text])=>`<option value="${esc(id)}" ${String(id)===String(value)?'selected':''}>${esc(text)}</option>`).join('')}</select></label>`;
  }
  function field(label,name,value='',{required=false,max=200,type='text',full=false,span=false,placeholder=''}={}) {
    return `<label class="field${full?' full':''}${span?' span-2':''}"><span>${esc(label)}${required?' <b class="required">*</b>':''}</span>${type==='textarea'?`<textarea name="${esc(name)}" maxlength="${max}" ${required?'required':''} placeholder="${esc(placeholder)}">${esc(value)}</textarea>`:`<input name="${esc(name)}" type="${type}" value="${esc(value)}" ${type==='number'?'min="0" step="1"':`maxlength="${max}"`} ${required?'required':''} placeholder="${esc(placeholder)}">`}</label>`;
  }
  function refOptions(resource, selected = null, onlyEnabled = false) {
    return (state.refs?.[resource] || []).filter(r=>!onlyEnabled||r.status==='enabled'||r.id===selected)
      .map(r=>[r.id, r.name+(r.status==='disabled'?'（已禁用）':'')]);
  }
  async function loadRefs(force = false) {
    if (state.refs && !force) return;
    const entries = await Promise.all(Object.keys(names).map(async resource => {
      let page=1, rows=[], result;
      do { result=await api(`/api/v1/${resource}?page_size=100&page=${page++}`);rows.push(...result.items); }
      while(rows.length < result.total);
      return [resource,rows];
    }));
    state.refs=Object.fromEntries(entries);
  }
  function renderFilters() {
    const f=state.filters;
    $('#filters').innerHTML=state.resource==='ledgers' ?
      select('TC系统名称','tc_system_id',refOptions('tc-systems'),f.tc_system_id)+
      select('事业部','division_id',refOptions('divisions'),f.division_id)+
      select('集成方系统名称','partner_system_id',refOptions('partner-systems'),f.partner_system_id)+
      select('系统集成状态','integration_status',statuses,f.integration_status)+
      field('TC负责人','tc_contact',f.tc_contact,{max:100,placeholder:'输入负责人姓名'})+
      field('集成方负责人','partner_contact',f.partner_contact,{max:100,placeholder:'输入负责人姓名'})+
      select('接口提供方','provider_side',providers,f.provider_side)+
      field('维护人','maintainer',f.maintainer,{max:100,placeholder:'输入维护人'})+
      field('关键词','q',f.q,{placeholder:'系统名称 / 台账备注'}) :
      field(names[state.resource],'name',f.name,{placeholder:'输入名称查询'})+
      field('维护人','maintainer',f.maintainer,{max:100,placeholder:'输入维护人'})+
      select('状态','status',baseStatuses,f.status);
  }
  async function navigate() {
    const next=location.hash.slice(1) || 'ledgers';
    if(!titles[next]) {location.hash='ledgers';return;}
    state.resource=next;state.page=1;state.filters={};
    const sequence=++state.sequence;
    requestController?.abort();
    $('#table-scroll').scrollLeft=0;
    $$('dialog[open]').forEach(d=>{if(!state.saving)d.close();});
    $$('[data-nav]').forEach(node=>{
      node.classList.toggle('active',node.dataset.nav===next);
      if(node.dataset.nav===next)node.setAttribute('aria-current','page');else node.removeAttribute('aria-current');
    });
    $('#page-title').textContent=titles[next];document.title=titles[next]+' · 系统集成台账';
    $('#breadcrumb').textContent=next==='ledgers'?'业务管理 / 台账':'基础数据管理 / '+titles[next];
    $('#page-description').textContent=next==='ledgers'?'统一维护系统之间的集成关系与接口信息':'维护可用于系统集成台账的'+names[next];
    $('#list-title').textContent=next==='ledgers'?'台账列表':titles[next]+'列表';
    $('#add-button').textContent=next==='ledgers'?'＋ 新增台账':'＋ 新增';
    $('#export-button').hidden=next!=='ledgers';
    $('#table-body').innerHTML='<tr><td colspan="12" class="empty-cell">正在加载…</td></tr>';
    try {
      if(next==='ledgers')await loadRefs(true);
      if(sequence!==state.sequence)return;
      renderFilters();await loadRecords();
    } catch(error) {if(sequence===state.sequence){errorBox('#page-error',error.message);renderEmpty('数据加载失败','请检查连接后点击刷新。');}}
  }
  function renderEmpty(title,subtitle) {
    $('#table-body').innerHTML=`<tr><td colspan="12" class="empty-cell"><div class="empty-title">${esc(title)}</div>${esc(subtitle)}</td></tr>`;
  }
  async function loadRecords() {
    requestController?.abort();requestController=new AbortController();
    const sequence=++state.sequence, resource=state.resource;
    errorBox('#page-error','');
    $('#previous-page').disabled=true;$('#next-page').disabled=true;
    $('#record-count').textContent='正在读取数据';
    const headers=resource==='ledgers'?['序号','TC系统名称','事业部','集成方系统','集成状态','TC / 集成方负责人','接口','维护人','更新时间','操作']:['序号',names[resource],'维护人','状态','创建时间','更新时间','操作'];
    $('#data-table').className=resource==='ledgers'?'ledger-table':'base-table';
    $('#table-head').innerHTML=`<tr>${headers.map(h=>`<th>${esc(h)}</th>`).join('')}</tr>`;
    renderEmpty('正在加载…','');
    const query=new URLSearchParams({...state.filters,page:state.page,page_size:state.size});
    try {
      const data=await api(`/api/v1/${resource}?${query}`,{signal:requestController.signal});
      if(sequence!==state.sequence)return;
      state.total=data.total;
      const maxPage=Math.max(1,Math.ceil(data.total/state.size));
      if(state.page>maxPage){state.page=maxPage;return loadRecords();}
      state.rows=new Map(data.items.map(r=>[String(r.id),r]));
      $('#record-count').textContent=`共 ${data.total} 条记录`;
      $('#table-body').innerHTML=data.items.map((r,n)=>renderRow(r,n)).join('');
      if(!data.items.length)renderEmpty('暂无符合条件的数据','可调整查询条件，或点击新增。');
      $('#page-summary').textContent=data.total?`显示第 ${(state.page-1)*state.size+1}–${Math.min(state.page*state.size,data.total)} 条，共 ${data.total} 条`:'共 0 条记录';
      $('#page-number').textContent=`${state.page} / ${maxPage}`;
      $('#previous-page').disabled=state.page<=1;$('#next-page').disabled=state.page>=maxPage;
    } catch(error) {
      if(error.name==='AbortError'||sequence!==state.sequence)return;
      errorBox('#page-error',error.message);renderEmpty('数据加载失败','点击刷新重试');$('#record-count').textContent='读取失败';
    }
  }
  function renderRow(r,n) {
    const number=(state.page-1)*state.size+n+1;
    const actions=`<td class="actions-cell">${state.resource==='ledgers'?`<button type="button" class="text-button" data-action="detail" data-id="${r.id}">详情</button>`:''}<button type="button" class="text-button" data-action="edit" data-id="${r.id}">编辑</button><button type="button" class="text-button danger" data-action="delete" data-id="${r.id}">删除</button></td>`;
    if(state.resource!=='ledgers')return `<tr><td class="muted">${number}</td><td class="system-name">${esc(r.name)}</td><td>${esc(r.maintainer_name||'—')}</td><td>${badge(r.status,true)}</td><td class="muted nowrap">${date(r.created_at)}</td><td class="muted nowrap">${date(r.updated_at)}</td>${actions}</tr>`;
    const contacts=side=>r.contacts.filter(c=>c.contact_side===side).map(c=>c.contact_name).join('、')||'—';
    return `<tr data-row="${r.id}"><td class="muted">${number}</td><td class="system-name">${esc(r.tc_system_name)}</td><td><div class="chips">${r.divisions.map(d=>`<span class="chip">${esc(d.name)}</span>`).join('')}</div></td><td>${esc(r.partner_system_name)}</td><td>${badge(r.integration_status)}</td><td>${esc(contacts('tc'))}<span class="subline">集成方：${esc(contacts('partner'))}</span></td><td><button class="count-button" type="button" data-action="expand" data-id="${r.id}" aria-expanded="false">${r.interfaces.length} 个接口 ▾</button></td><td>${esc(r.maintainer_name||'—')}</td><td class="muted nowrap">${date(r.updated_at)}</td>${actions}</tr>`;
  }
  function interfaceTable(interfaces) {
    return `<div class="table-scroll"><table class="detail-table"><thead><tr><th>接口名称 / 提供方</th><th>接口状态</th><th>测试 / 生产地址</th><th>nacos服务名</th><th>接口报文样例</th><th>日志路径</th><th>备注</th></tr></thead><tbody>${interfaces.map(i=>`<tr><td><strong>${esc(i.interface_name)}</strong><div style="margin-top:6px"><span class="provider-badge${i.provider_side==='non_tc'?' external':''}">${i.provider_side==='tc'?'TC提供':'非TC提供'}</span></div></td><td>${badge(i.integration_status)}${i.effective_status!==i.integration_status?'<span class="subline">所属台账已作废</span>':''}</td><td>测试：${esc(i.test_url||'—')}<span class="subline">生产：${esc(i.prod_url||'—')}</span></td><td>测试：${esc(i.test_nacos_service_name||'—')}<span class="subline">生产：${esc(i.prod_nacos_service_name||'—')}</span></td><td><pre>${esc(i.message_sample||'—')}</pre></td><td>测试：${esc(i.test_log_path||'—')}<span class="subline">生产：${esc(i.prod_log_path||'—')}</span></td><td>${esc(i.remark||'—')}</td></tr>`).join('')}</tbody></table></div>`;
  }
  function expandRow(button,record) {
    const row=button.closest('tr');const existing=row.nextElementSibling;
    if(existing?.classList.contains('inline-detail')) {existing.remove();row.classList.remove('expanded');button.setAttribute('aria-expanded','false');return;}
    const detail=document.createElement('tr');detail.className='inline-detail';
    detail.innerHTML=`<td colspan="10"><div class="details-heading"><strong>接口明细 <span class="muted">${record.interfaces.length} 个接口</span></strong><button class="text-button" data-action="detail" data-id="${record.id}">查看完整台账 →</button></div>${interfaceTable(record.interfaces)}</td>`;
    row.after(detail);row.classList.add('expanded');button.setAttribute('aria-expanded','true');
  }
  async function openDetail(id) {
    const r=await api(`/api/v1/ledgers/${id}`);
    $('#detail-title').textContent=r.tc_system_name+' → '+r.partner_system_name;
    const contacts=side=>r.contacts.filter(c=>c.contact_side===side).map(c=>c.contact_name).join('、');
    const rows=[['TC系统',r.tc_system_name],['集成方系统',r.partner_system_name],['事业部',r.divisions.map(d=>d.name).join('、')],['系统集成状态',r.integration_status==='active'?'生效':'作废'],['TC负责人',contacts('tc')],['集成方负责人',contacts('partner')],['集成方测试IP',r.test_ip],['集成方生产IP',r.prod_ip],['维护人',r.maintainer_name],['涉及首选项',r.preferences_text],['涉及handle',r.handle_text],['基本信息备注',r.remark]];
    $('#detail-content').innerHTML=`<div class="detail-grid">${rows.map(([label,value])=>`<div class="detail-item"><span>${esc(label)}</span><div>${esc(value||'—')}</div></div>`).join('')}</div><section class="detail-section"><h3>接口明细 · ${r.interfaces.length}</h3>${interfaceTable(r.interfaces)}</section><section class="detail-section"><h3>关联文档 · ${r.attachments.length}</h3>${r.attachments.length?r.attachments.map(a=>`<div class="document-row"><span class="file-name">${esc(a.file_name)}</span><span class="muted">${a.storage_source==='dimou'?'迪墨云':'本地文件'}</span><span class="muted">${esc(a.storage_ref)}</span></div>`).join(''):'<p class="muted">未登记关联文档</p>'}</section>`;
    $('#detail-audit').textContent=`创建：${date(r.created_at)} · 更新：${date(r.updated_at)} · 版本 ${r.version}`;
    $('#detail-dialog').showModal();
  }
  function section(title,body,action='') {return `<section class="form-section"><div class="section-title"><h3>${title}</h3>${action}</div>${body}</section>`;}
  function apiCard(record={}) {
    return `<div class="api-card" data-child-id="${record.id||''}"><div class="api-card-header"><strong>接口信息</strong><button type="button" class="text-button danger" data-remove="api-card">移除此接口</button></div><div class="form-grid api-grid">${select('接口提供方','provider_side',providers,record.provider_side||'tc',{required:true,empty:null})}${field('接口清单名称','interface_name',record.interface_name,{required:true})}${select('接口集成状态','integration_status',statuses,record.integration_status||'active',{empty:null})}${field('测试nacos服务名','test_nacos_service_name',record.test_nacos_service_name,{max:255})}${field('生产nacos服务名','prod_nacos_service_name',record.prod_nacos_service_name,{max:255})}<div></div>${field('测试环境接口地址','test_url',record.test_url,{type:'url',max:2048})}${field('生产环境接口地址','prod_url',record.prod_url,{type:'url',max:2048})}${field('详细信息备注','remark',record.remark,{max:10000})}</div><details ${record.message_sample?'open':''}><summary>报文样例和日志路径</summary><div class="form-grid">${field('接口报文样例','message_sample',record.message_sample,{type:'textarea',full:true,max:1000000})}${field('测试日志路径','test_log_path',record.test_log_path,{max:2048})}${field('生产日志路径','prod_log_path',record.prod_log_path,{max:2048})}</div></details></div>`;
  }
  function attachmentCard(a={}) {
    return `<div class="attachment-card" data-child-id="${a.id||''}"><div class="attachment-card-header"><span>文档信息</span><button type="button" class="text-button danger" data-remove="attachment-card">移除文档</button></div><div class="form-grid">${field('文档名称','file_name',a.file_name,{required:true,max:255})}${select('文档来源','storage_source',[['dimou','迪墨云'],['local','本地文件']],a.storage_source||'dimou',{empty:null})}${field('文件永久ID / 存储Key','storage_ref',a.storage_ref,{required:true,max:1024,full:true})}${field('文件大小（字节）','file_size',a.file_size,{type:'number'})}${field('文件类型','content_type',a.content_type,{max:127,placeholder:'例如 application/pdf'})}</div></div>`;
  }
  async function openEditor(id=null) {
    const resource=state.resource;
    const record=id?await api(`/api/v1/${resource}/${id}`):null;
    if(resource==='ledgers')await loadRefs(true);
    state.editor={resource,record};state.saving=false;
    $('#editor-fields').inert=false;$('#save-button').disabled=false;
    $$('#editor-dialog [data-close]').forEach(b=>b.disabled=false);
    errorBox('#editor-error','');$('#reload-editor').hidden=true;
    $('#editor-title').textContent=(record?'编辑':'新增')+(resource==='ledgers'?'台账':names[resource]);
    $('#editor-eyebrow').textContent=titles[resource];
    $('#editor-dialog').classList.toggle('base-editor',resource!=='ledgers');
    if(resource!=='ledgers') {
      $('#editor-fields').innerHTML=`<div class="form-grid">${field(names[resource],'name',record?.name,{required:true,full:true})}${field('维护人','maintainer_name',record?.maintainer_name,{max:100})}${select('状态','status',baseStatuses,record?.status||'enabled',{empty:null})}</div>`;
    } else {
      const r=record||{}, divisions=new Set(r.division_ids||[]);
      const contacts=side=>(r.contacts||[]).filter(c=>c.contact_side===side).map(c=>c.contact_name).join('，');
      const options=(state.refs.divisions||[]).filter(d=>d.status==='enabled'||divisions.has(d.id));
      const divisionField=`<div class="field full"><span>事业部（支持多选） <b class="required">*</b></span><div class="division-options">${options.map(d=>`<label class="division-option"><input type="checkbox" name="division_ids" value="${d.id}" ${divisions.has(d.id)?'checked':''}>${esc(d.name)}${d.status==='disabled'?'（已禁用）':''}</label>`).join('')||'<span class="muted">请先在事业部管理中添加启用的事业部</span>'}</div></div>`;
      const basic=`<div class="form-grid">${select('TC系统名称','tc_system_id',refOptions('tc-systems',r.tc_system_id,true),r.tc_system_id,{required:true,empty:'请选择TC系统'})}${select('集成方系统名称','partner_system_id',refOptions('partner-systems',r.partner_system_id,true),r.partner_system_id,{required:true,empty:'请选择集成方系统'})}${divisionField}${field('TC负责人（支持多人）','tc_contacts',contacts('tc'),{max:10000,placeholder:'多人用逗号分隔'})}${field('集成方负责人（支持多人）','partner_contacts',contacts('partner'),{max:10000,placeholder:'多人用逗号分隔'})}${field('集成方测试IP','test_ip',r.test_ip,{max:45,placeholder:'IPv4 或 IPv6'})}${field('集成方生产IP','prod_ip',r.prod_ip,{max:45,placeholder:'IPv4 或 IPv6'})}${select('系统集成状态','integration_status',statuses,r.integration_status||'active',{empty:null})}${field('维护人','maintainer_name',r.maintainer_name,{max:100})}${field('涉及首选项','preferences_text',r.preferences_text,{max:10000})}${field('涉及handle','handle_text',r.handle_text,{max:10000})}${field('基本信息备注','remark',r.remark,{type:'textarea',full:true,max:10000})}</div>`;
      $('#editor-fields').innerHTML=section('基础信息',basic)+section('接口明细',`<div id="interface-cards">${(r.interfaces?.length?r.interfaces:[{}]).map(apiCard).join('')}</div>`,'<button type="button" class="button secondary small" id="add-interface">＋ 添加接口</button>')+section('关联文档',`<p class="section-note">登记已有文档的名称和永久标识。</p><div id="attachment-cards">${(r.attachments||[]).map(attachmentCard).join('')}</div><div id="no-attachments" class="no-attachments" ${r.attachments?.length?'hidden':''}>暂无关联文档</div>`,'<button type="button" class="button secondary small" id="add-attachment">＋ 登记文档</button>');
    }
    $('#editor-dialog').showModal();
    $('#editor-dialog .dialog-body').scrollTop=0;
  }
  function read(container,name) {return $(`[name="${name}"]`,container)?.value.trim()||null;}
  function collectContacts(form,record) {
    const available=(record?.contacts||[]).map(c=>({...c,used:false}));
    const result=[];
    for(const side of ['tc','partner']) {
      const names=(read(form,side+'_contacts')||'').split(/[,，、;；\n]/).map(v=>v.trim()).filter(Boolean);
      names.forEach((name,n)=>{
        const old=available.find(c=>!c.used&&c.contact_side===side&&c.contact_name===name);
        if(old)old.used=true;
        result.push({...old?{id:old.id}:{},contact_side:side,contact_name:name,sort_no:n});
      });
    }
    return result;
  }
  function collectPayload() {
    const form=$('#editor-form'), {resource,record}=state.editor;
    if(resource!=='ledgers')return {name:read(form,'name'),maintainer_name:read(form,'maintainer_name'),status:read(form,'status')};
    const result={tc_system_id:Number(read(form,'tc_system_id')),partner_system_id:Number(read(form,'partner_system_id')),
      division_ids:$$('[name="division_ids"]:checked',form).map(n=>Number(n.value)),contacts:collectContacts(form,record)};
    if(!result.division_ids.length)throw new Error('请至少选择一个事业部。');
    ['test_ip','prod_ip','integration_status','preferences_text','handle_text','remark','maintainer_name'].forEach(key=>result[key]=read(form,key));
    result.interfaces=$$('.api-card',form).map((card,n)=>{
      const row={sort_no:n};if(card.dataset.childId)row.id=Number(card.dataset.childId);
      ['provider_side','interface_name','integration_status','test_nacos_service_name','prod_nacos_service_name','test_url','prod_url','message_sample','test_log_path','prod_log_path','remark'].forEach(key=>row[key]=read(card,key));
      return row;
    });
    result.attachments=$$('.attachment-card',form).map((card,n)=>{
      const row={sort_no:n};if(card.dataset.childId)row.id=Number(card.dataset.childId);
      ['file_name','storage_source','storage_ref','content_type'].forEach(key=>row[key]=read(card,key));
      row.file_size=read(card,'file_size')===null?null:Number(read(card,'file_size'));return row;
    });
    if(record)result.version=record.version;
    return result;
  }
  function editorBusy(busy) {
    state.saving=busy;$('#editor-fields').inert=busy;$('#save-button').disabled=busy;
    $('#save-button').textContent=busy?'正在保存…':'保存';
    $$('#editor-dialog [data-close]').forEach(b=>b.disabled=busy);
  }
  async function save(event) {
    event.preventDefault();if(state.saving)return;
    errorBox('#editor-error','');
    try {
      const body=collectPayload(), {resource,record}=state.editor;
      editorBusy(true);
      await api(`/api/v1/${resource}${record?'/'+record.id:''}`,{method:record?'PUT':'POST',body:JSON.stringify(body)});
      $('#editor-dialog').close();notify('保存成功');state.refs=null;
      if(state.resource===resource)await loadRecords();
    } catch(error) {
      errorBox('#editor-error',error.message);$('#editor-dialog .dialog-body').scrollTop=0;
      $('#reload-editor').hidden=!(error.status===409&&state.editor?.resource==='ledgers'&&state.editor.record);
    } finally {editorBusy(false);}
  }
  function confirmDelete(record) {
    const resource=state.resource;
    pendingDelete={resource,record};errorBox('#confirm-error','');
    $('#confirm-message').textContent=resource==='ledgers'?`将删除「${record.tc_system_name} → ${record.partner_system_name}」及其接口、负责人和文档关联记录。\n删除后无法恢复。`:`将删除「${record.name}」。\n被台账引用的数据无法删除，可改为禁用。`;
    $('#confirm-delete').disabled=false;$('#confirm-dialog').showModal();
  }
  async function deleteRecord() {
    if(!pendingDelete)return;
    const {resource,record}=pendingDelete;$('#confirm-delete').disabled=true;errorBox('#confirm-error','');
    try {
      await api(`/api/v1/${resource}/${record.id}${resource==='ledgers'?'?version='+record.version:''}`,{method:'DELETE'});
      $('#confirm-dialog').close();pendingDelete=null;state.refs=null;notify('删除成功');
      if(state.resource===resource)await loadRecords();
    } catch(error) {errorBox('#confirm-error',error.message);} finally {$('#confirm-delete').disabled=false;}
  }
  async function exporting() {
    const button=$('#export-button');button.disabled=true;
    try {
      const blob=await api('/api/v1/ledgers/export?'+new URLSearchParams(state.filters),{blob:true});
      const url=URL.createObjectURL(blob),anchor=document.createElement('a');anchor.href=url;
      anchor.download='系统集成台账_'+new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai'}).format(new Date())+'.csv';
      document.body.append(anchor);anchor.click();anchor.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);
      notify('台账已导出');
    } catch(error) {notify(error.message,true);} finally {button.disabled=false;}
  }
  $('#filter-form').addEventListener('submit',event=>{event.preventDefault();state.filters=Object.fromEntries([...new FormData(event.currentTarget)].filter(([,v])=>String(v).trim()).map(([k,v])=>[k,String(v).trim()]));state.page=1;loadRecords();});
  $('#filter-form').addEventListener('reset',event=>{event.preventDefault();state.filters={};state.page=1;renderFilters();loadRecords();});
  $('#reload-button').addEventListener('click',()=>{if(!$('#filters').children.length)navigate();else loadRecords();});
  $('#page-size').addEventListener('change',event=>{state.size=Number(event.target.value);state.page=1;loadRecords();});
  $('#previous-page').addEventListener('click',()=>{state.page--;loadRecords();});
  $('#next-page').addEventListener('click',()=>{state.page++;loadRecords();});
  $('#add-button').addEventListener('click',()=>openEditor().catch(e=>notify(e.message,true)));
  $('#export-button').addEventListener('click',exporting);
  $('#editor-form').addEventListener('submit',save);
  $('#confirm-delete').addEventListener('click',deleteRecord);
  $('#reload-editor').addEventListener('click',()=>{
    const id=state.editor?.record?.id;if(id){$('#editor-dialog').close();openEditor(id).catch(e=>notify(e.message,true));}
  });
  $('#editor-dialog').addEventListener('cancel',event=>{if(state.saving)event.preventDefault();});
  $('#editor-fields').addEventListener('click',event=>{
    if(event.target.closest('#add-interface'))$('#interface-cards').insertAdjacentHTML('beforeend',apiCard());
    if(event.target.closest('#add-attachment')){$('#attachment-cards').insertAdjacentHTML('beforeend',attachmentCard());$('#no-attachments').hidden=true;}
    const remove=event.target.closest('[data-remove]');
    if(remove){
      if(remove.dataset.remove==='api-card'&&$$('.api-card').length===1){notify('至少保留一个接口',true);return;}
      remove.closest('.'+remove.dataset.remove).remove();
      if($('#no-attachments'))$('#no-attachments').hidden=$$('.attachment-card').length>0;
    }
  });
  document.addEventListener('click',event=>{
    const close=event.target.closest('[data-close]');if(close){$('#'+close.dataset.close).close();return;}
    const button=event.target.closest('[data-action]');if(!button)return;
    const record=state.rows.get(button.dataset.id);if(!record)return;
    if(button.dataset.action==='expand'){expandRow(button,record);return;}
    if(button.dataset.action==='delete'){confirmDelete(record);return;}
    button.disabled=true;
    (button.dataset.action==='detail'?openDetail(record.id):openEditor(record.id)).catch(e=>notify(e.message,true)).finally(()=>{button.disabled=false;});
  });
  window.addEventListener('hashchange',navigate);
  api('/health').catch(()=>connected(false));
  navigate();
})();
