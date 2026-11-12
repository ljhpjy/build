package com.dsmod.deepseek;

/** Dark, compact Chinese admin console for the local API. */
final class AdminPage {
    private AdminPage() {}

    static String build(int port) {
        return PAGE.replace("%PORT%", String.valueOf(port));
    }

    private static final String PAGE = """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DSMod · DeepSeek 本地 API</title>
<style>
:root{
  --bg:#060912;--panel:rgba(17,25,42,.76);--line:rgba(122,182,255,.16);--text:#e8f2ff;
  --muted:#8194b6;--cyan:#22d3ee;--violet:#8b5cf6;--green:#34d399;--red:#f87171;--amber:#fbbf24;
}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;color:var(--text);font-family:-apple-system,"PingFang SC","Microsoft YaHei",system-ui,sans-serif;
background:
 radial-gradient(1100px 560px at 8% -12%,rgba(139,92,246,.28),transparent 62%),
 radial-gradient(860px 480px at 108% 6%,rgba(34,211,238,.2),transparent 58%),
 var(--bg);}
.wrap{max-width:1060px;margin:0 auto;padding:26px 16px 64px}
header{display:flex;align-items:center;justify-content:space-between;gap:14px;flex-wrap:wrap;margin-bottom:20px}
h1{margin:0;font-size:19px;letter-spacing:.4px}
h1 em{font-style:normal;background:linear-gradient(90deg,var(--cyan),var(--violet));-webkit-background-clip:text;background-clip:text;color:transparent}
.badge{display:inline-flex;align-items:center;gap:8px;padding:7px 13px;border:1px solid var(--line);border-radius:999px;background:var(--panel);font-size:13px}
.dot{width:8px;height:8px;border-radius:50%;background:var(--green);box-shadow:0 0 14px var(--green);animation:pulse 1.7s infinite}
.dot.off{background:var(--red);box-shadow:0 0 14px var(--red);animation:none}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.32}}
.grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin-bottom:16px}
.card{position:relative;border:1px solid var(--line);border-radius:16px;background:var(--panel);backdrop-filter:blur(14px);padding:16px;margin-bottom:16px;overflow:hidden}
.card::before{content:"";position:absolute;left:0;right:0;top:0;height:1px;background:linear-gradient(90deg,transparent,rgba(34,211,238,.65),transparent)}
.card h2{margin:0 0 12px;font-size:12px;font-weight:600;letter-spacing:1.2px;color:var(--muted);text-transform:uppercase}
.metric{font-size:26px;font-weight:700;font-variant-numeric:tabular-nums}
.metric small{font-size:12px;font-weight:400;color:var(--muted);margin-left:4px}
table{width:100%;border-collapse:collapse;font-size:12px;table-layout:auto}
th{color:var(--muted);font-weight:500;text-align:left;padding:8px 8px;border-bottom:1px solid var(--line);white-space:normal}
td{padding:9px 8px;border-bottom:1px solid rgba(122,182,255,.08);white-space:normal;word-break:break-all;vertical-align:top}
tbody tr:hover td{background:rgba(34,211,238,.05)}
.tag{display:inline-block;padding:2px 9px;border-radius:999px;font-size:11px;border:1px solid var(--line)}
.tag.ok{color:var(--green);border-color:rgba(52,211,153,.4);background:rgba(52,211,153,.1)}
.tag.warn{color:var(--amber);border-color:rgba(251,191,36,.4);background:rgba(251,191,36,.1)}
.tag.err{color:var(--red);border-color:rgba(248,113,113,.4);background:rgba(248,113,113,.1)}
.chip{padding:5px 12px;border-radius:999px;font-size:12px;background:transparent}
.chip.active{background:rgba(34,211,238,.18);border-color:rgba(34,211,238,.6)}
input,select,textarea,button{font:inherit;color:var(--text);background:rgba(7,12,22,.88);border:1px solid var(--line);border-radius:10px;padding:9px 11px;outline:none}
input:focus,select:focus,textarea:focus{border-color:rgba(34,211,238,.55);box-shadow:0 0 0 3px rgba(34,211,238,.12)}
textarea{width:100%;resize:vertical;font-family:ui-monospace,Menlo,monospace;font-size:12px;line-height:1.6}
button{cursor:pointer;transition:.18s;background:linear-gradient(180deg,rgba(34,211,238,.2),rgba(139,92,246,.16))}
button:hover{border-color:rgba(34,211,238,.6);transform:translateY(-1px)}
button.ghost{background:transparent}
button.danger{background:rgba(248,113,113,.14)}
.row{display:flex;gap:10px;flex-wrap:wrap;align-items:flex-end}
.field{display:flex;flex-direction:column;gap:5px;font-size:12px;color:var(--muted)}
pre{margin:0;background:rgba(3,7,14,.9);border:1px solid var(--line);border-radius:12px;padding:12px;font-size:12px;line-height:1.6;max-height:280px;overflow:auto;font-family:ui-monospace,Menlo,monospace;color:#b9d4ff}
.muted{color:var(--muted);font-size:12px}
a{color:var(--cyan)}
@media(max-width:720px){.grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
</style>
</head>
<body>
<div class="wrap">
<header>
  <h1>DSMod · <em>DeepSeek 本地 API</em></h1>
  <div class="badge"><span id="dot" class="dot"></span><span id="state">检测中</span></div>
  <input id="adminKey" placeholder="API Key（可选）" style="width:170px" onchange="localStorage.setItem('dsmod_key',this.value)">
</header>

<div class="grid">
  <div class="card"><h2>请求总数</h2><div class="metric" id="mReq">—</div></div>
  <div class="card"><h2>异常数</h2><div class="metric" id="mErr">—</div></div>
  <div class="card"><h2>在线账号</h2><div class="metric" id="mAcc">—<small id="mAccSub"></small></div></div>
  <div class="card"><h2>运行时长</h2><div class="metric" id="mUp">—</div></div>
</div>

<div class="card">
  <h2>账号池</h2>
  <div id="accounts"><span class="muted">加载中…</span></div>
  <div class="row" style="margin-top:12px">
    <input id="tokenAdd" placeholder="粘贴 userToken（当前登录号会自动入池）" style="flex:1;min-width:220px">
    <button onclick="addToken()">添加账号</button>
    <button class="ghost" onclick="verifyPool()">验证全部</button>
    <button class="ghost" onclick="resetCooldown()">清除冷却</button>
    <span id="poolmsg" class="muted"></span>
  </div>
  <textarea id="tokens" rows="3" placeholder="一行一个 userToken；点“整体替换”覆盖整个号池"></textarea>
  <div class="row" style="margin-top:8px">
    <button onclick="savePool()">整体替换</button>
    <span class="muted">在 DeepSeek 内切换账号后 5 秒内自动入池</span>
  </div>
</div>

<div class="card">
  <h2>配置</h2>
  <div class="row">
    <label class="field">API Key（留空不修改）<input id="cApiKey" placeholder="未设置" style="width:220px"></label>
    <label class="field">思考模式<select id="cThinkingMode"><option>always</option><option>auto</option><option>never</option></select></label>
    <label class="field">默认思考<input id="cThinking" type="checkbox"></label>
    <label class="field">联网搜索<input id="cSearch" type="checkbox"></label>
    <label class="field">并发上限<input id="cConcurrency" type="number" min="1" style="width:80px"></label>
  </div>
  <div class="row" style="margin-top:10px">
    <label class="field">Prompt 字符上限<input id="cMaxPrompt" type="number" style="width:130px"></label>
    <label class="field">附件字符上限<input id="cMaxAttachment" type="number" style="width:140px"></label>
    <label class="field">请求超时 (ms)<input id="cTimeout" type="number" style="width:140px"></label>
  </div>
  <div class="row" style="margin-top:10px">
    <button onclick="saveConfig()">保存配置</button>
    <span id="cfgmsg" class="muted"></span>
  </div>
</div>

<div class="card">
  <h2>运行日志（尾部 300 行 · 已自动去敏）</h2>
  <div class="row" style="margin-bottom:8px">
    <button class="chip active" data-cat="" onclick="setLogCat('')">全部</button>
    <button class="chip" data-cat="MAIN" onclick="setLogCat('MAIN')">注入</button>
    <button class="chip" data-cat="HTTP" onclick="setLogCat('HTTP')">请求</button>
    <button class="chip" data-cat="ACCOUNT" onclick="setLogCat('ACCOUNT')">账号</button>
    <button class="chip" data-cat="UPSTREAM" onclick="setLogCat('UPSTREAM')">上游</button>
    <button class="chip" data-cat="CHAT" onclick="setLogCat('CHAT')">对话</button>
    <button class="chip" data-cat="CONFIG" onclick="setLogCat('CONFIG')">配置</button>
    <input id="logSearch" placeholder="关键字过滤" style="width:150px" oninput="renderLogs()">
  </div>
  <div class="row" style="margin-bottom:10px">
    <button class="ghost" onclick="load()">刷新</button>
    <button class="ghost" onclick="copyLogs()">复制日志</button>
    <button class="ghost" onclick="exportLogs()">导出日志</button>
    <button class="ghost" onclick="clearLogs()">清空日志</button>
    <span id="logmsg" class="muted"></span>
  </div>
  <pre id="logs">加载中…</pre>
</div>

<p class="muted">
  监听 0.0.0.0:%PORT% ｜ OpenAI http://127.0.0.1:%PORT%/v1 ｜ Anthropic http://127.0.0.1:%PORT%
  ｜ <a href="/">接口说明</a>
</p>
</div>

<script>
var PORT = %PORT%;
var logCat = '';
var logRaw = '';
function gh(extra){
  var h = Object.assign({}, extra||{});
  var k = localStorage.getItem('dsmod_key')||'';
  if(k){ h['Authorization']='Bearer '+k; h['x-api-key']=k; }
  return h;
}
async function j(u,o){ o=o||{}; o.headers=gh(o.headers); var r=await fetch(u,o); return await r.json(); }
function esc(s){ return String(s===undefined||s===null?'':s).replace(/[&<>]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;'}[c]}); }
function fmtUp(s){ s=s||0; var d=Math.floor(s/86400),h=Math.floor(s%86400/3600),m=Math.floor(s%3600/60),ss=s%60; return (d?d+'天 ':'')+(h?h+'时 ':'')+m+'分 '+ss+'秒'; }
function fmtCool(ms){ ms=ms||0; return ms>0?Math.ceil(ms/1000)+'s':'-'; }

async function load(){
  try{
    var s = await j('/healthz');
    var ok = s.status==='ok';
    document.getElementById('dot').className = 'dot'+(ok?'':' off');
    document.getElementById('state').textContent = ok?'运行中':'已停止';
    document.getElementById('mReq').textContent = s.requests;
    document.getElementById('mErr').textContent = s.errors;
    document.getElementById('mAcc').innerHTML = (s.accounts_active===undefined?0:s.accounts_active)+'<small> / '+s.accounts+' 个</small>';
    document.getElementById('mUp').textContent = fmtUp(s.uptime_sec);
  }catch(e){
    document.getElementById('dot').className='dot off';
    document.getElementById('state').textContent='连接失败';
  }
  try{
    var a = await j('/admin/api/accounts');
    var h = '<table><thead><tr><th>#</th><th>Token</th><th>状态</th><th>请求</th><th>冷却</th><th>最后错误</th><th></th></tr></thead><tbody>';
    (a.accounts||[]).forEach(function(x){
      var st = x.authFailed?'<span class="tag err">已停用</span>':(x.cooldownMs>0?'<span class="tag warn">冷却中</span>':'<span class="tag ok">可用</span>');
      h += '<tr><td>'+(x.current?'★ ':'')+x.index+'</td><td>'+esc(x.token)+'</td><td>'+st+'</td><td>'+x.requests+'</td><td>'+fmtCool(x.cooldownMs)+'</td><td>'+esc(x.lastError||'-')+'</td><td><button class="danger" onclick="removeToken('+x.index+')">删除</button></td></tr>';
    });
    h += '</tbody></table>';
    if(a.token_error){ h += '<p class="muted">Token 读取失败：'+esc(a.token_error)+'</p>'; }
    else if(!a.accounts||a.accounts.length===0){ h += '<p class="muted">尚未获取账号：请打开 DeepSeek 登录，5 秒内自动入池</p>'; }
    document.getElementById('accounts').innerHTML = h;
  }catch(e){
    document.getElementById('accounts').innerHTML = '<span class="muted">号池读取失败：'+esc(e)+'</span>';
  }
  try{
    var c = await j('/admin/api/config');
    document.getElementById('cApiKey').value='';
    document.getElementById('cApiKey').placeholder = c.apiKeySet?'已设置（留空不修改）':'未设置';
    if(c.thinkingMode) document.getElementById('cThinkingMode').value=c.thinkingMode;
    document.getElementById('cThinking').checked=!!c.thinking;
    document.getElementById('cSearch').checked=!!c.search;
    document.getElementById('cConcurrency').value=c.concurrency;
    document.getElementById('cMaxPrompt').value=c.maxPromptChars;
    document.getElementById('cMaxAttachment').value=c.maxAttachmentChars;
    document.getElementById('cTimeout').value=c.requestTimeoutMs;
  }catch(e){}
  try{
    var l = await j('/admin/api/logs' + (logCat ? ('?cat=' + encodeURIComponent(logCat)) : ''));
    logRaw = l.log || '';
    renderLogs();
  }catch(e){}
}

async function addToken(){
  var t = document.getElementById('tokenAdd').value.trim();
  if(!t){ document.getElementById('poolmsg').textContent='请输入 token'; return; }
  try{
    var r = await fetch('/admin/api/accounts/add',{method:'POST',headers:gh({'Content-Type':'application/json'}),body:JSON.stringify({token:t})});
    var d = await r.json();
    document.getElementById('poolmsg').textContent = d.added?'已添加':'已存在或无效';
    document.getElementById('tokenAdd').value='';
    load();
  }catch(e){ document.getElementById('poolmsg').textContent='错误: '+e; }
}

async function removeToken(i){
  if(!confirm('删除账号 #'+i+'？')) return;
  try{
    var r = await fetch('/admin/api/accounts/remove',{method:'POST',headers:gh({'Content-Type':'application/json'}),body:JSON.stringify({index:i})});
    var d = await r.json();
    document.getElementById('poolmsg').textContent = d.removed?'已删除':'未删除（当前登录号受保护）';
    load();
  }catch(e){ document.getElementById('poolmsg').textContent='错误: '+e; }
}

async function verifyPool(){
  document.getElementById('poolmsg').textContent='验证中…';
  try{
    var r = await fetch('/admin/api/accounts/verify',{method:'POST',headers:gh({'Content-Type':'application/json'}),body:'{}'});
    var d = await r.json();
    document.getElementById('poolmsg').textContent='有效 '+d.valid+' / '+d.total;
    load();
  }catch(e){ document.getElementById('poolmsg').textContent='错误: '+e; }
}

async function resetCooldown(){
  try{
    await fetch('/admin/api/accounts',{method:'POST',headers:gh({'Content-Type':'application/json'}),body:JSON.stringify({action:'reset'})});
    document.getElementById('poolmsg').textContent='已清除冷却/停用';
    load();
  }catch(e){ document.getElementById('poolmsg').textContent='错误: '+e; }
}

async function savePool(){
  var v = document.getElementById('tokens').value.split('\\n').map(function(s){return s.trim()}).filter(Boolean);
  if(!v.length){ document.getElementById('poolmsg').textContent='请输入 token'; return; }
  try{
    var r = await fetch('/admin/api/accounts',{method:'POST',headers:gh({'Content-Type':'application/json'}),body:JSON.stringify({tokens:v})});
    var d = await r.json();
    document.getElementById('poolmsg').textContent='已保存 '+d.saved+' 个';
    load();
  }catch(e){ document.getElementById('poolmsg').textContent='错误: '+e; }
}

async function saveConfig(){
  var b = {
    apiKey: document.getElementById('cApiKey').value,
    thinkingMode: document.getElementById('cThinkingMode').value,
    thinking: document.getElementById('cThinking').checked,
    search: document.getElementById('cSearch').checked,
    concurrency: Number(document.getElementById('cConcurrency').value),
    maxPromptChars: Number(document.getElementById('cMaxPrompt').value),
    maxAttachmentChars: Number(document.getElementById('cMaxAttachment').value),
    requestTimeoutMs: Number(document.getElementById('cTimeout').value)
  };
  try{
    await fetch('/admin/api/config',{method:'POST',headers:gh({'Content-Type':'application/json'}),body:JSON.stringify(b)});
    document.getElementById('cfgmsg').textContent='已保存';
    load();
  }catch(e){ document.getElementById('cfgmsg').textContent='错误: '+e; }
}

function renderLogs(){
  var q = (document.getElementById('logSearch').value||'').toLowerCase();
  var lines = (logRaw||'').split('\\n');
  if(q) lines = lines.filter(function(l){return l.toLowerCase().indexOf(q)>=0;});
  var el = document.getElementById('logs');
  el.textContent = lines.join('\\n') || '（空）';
  el.scrollTop = el.scrollHeight;
}
function setLogCat(cat){
  logCat = cat || '';
  var chips = document.querySelectorAll('.chip');
  for(var i=0;i<chips.length;i++){
    chips[i].classList.toggle('active', (chips[i].dataset.cat||'')===logCat);
  }
  load();
}
async function copyLogs(){
  try{
    var text = logRaw || '';
    if(!text){ document.getElementById('logmsg').textContent='没有日志'; return; }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(text);
    } else {
      var ta = document.createElement('textarea');
      ta.value = text;
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
    }
    document.getElementById('logmsg').textContent = '已复制 ' + text.length + ' 字符';
  }catch(e){ document.getElementById('logmsg').textContent = '复制失败: ' + e; }
}
function exportLogs(){
  location.href = '/admin/api/logs/export' + (logCat ? ('?cat=' + encodeURIComponent(logCat)) : '');
}
async function clearLogs(){
  if(!confirm('清空日志？')) return;
  try{
    await fetch('/admin/api/logs/clear', {method:'POST', headers:gh({})});
    document.getElementById('logmsg').textContent = '已清空';
    load();
  }catch(e){ document.getElementById('logmsg').textContent = '错误: ' + e; }
}

document.getElementById('adminKey').value=localStorage.getItem('dsmod_key')||'';
load();
setInterval(load, 5000);
</script>
</body>
</html>
""";
}
