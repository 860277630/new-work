'use strict';

const tools = {
  compress: { title: '图片压缩', description: '调整尺寸与质量，让图片更方便发送和保存。', hint: '支持 BMP/DIB、JPEG、GIF、TIFF、PNG、HEIC，单文件 ≤ 20MB', tag: '输出 JPG' },
  convertImage: { title: '图片格式转换', description: 'BMP/DIB、JPEG、GIF、TIFF、PNG、HEIC 相互转换，可选择位图颜色。JPEG 支持 jpg / jpeg / jpe / jfif，TIFF 支持 tif / tiff。', hint: '支持 BMP/DIB、JPEG、GIF、TIFF、PNG、HEIC，单文件 ≤ 20MB', tag: '多格式互转' },
  imagesToPdf: { title: '图片转 PDF', description: '把多张图片按顺序整理成一个 PDF，每张图片独立一页。', hint: '最多 9 张图片，支持 BMP/DIB、JPEG、GIF、TIFF、PNG、HEIC', tag: '输出 PDF' },
  pdfToImages: { title: 'PDF 转图片', description: '将 PDF 每一页转换成图片，生成 ZIP 压缩包。', hint: '支持未加密 PDF，最多 20 页，单文件不超过 20MB', tag: '输出 ZIP' }
};
const $ = id => document.getElementById(id);
const state = { operation: 'compress', files: [], busy: false, online: false, health: null, token: '', tokenExpires: 0,
  loginPromise: null, uploadedIds: [], job: null, resultUrl: null, toastTimer: null };
const MAX_FILE = 20 * 1024 * 1024;

function readStorage(key, fallback) {
  try { return JSON.parse(localStorage.getItem(key)) ?? fallback; } catch (_) { return fallback; }
}
function store(key, value) { try { localStorage.setItem(key, JSON.stringify(value)); } catch (_) {} }
function history() { const value = readStorage('format-factory-web-jobs', []); return Array.isArray(value) ? value.filter(job => job && typeof job.id === 'string') : []; }
function sizeLabel(size) { return size >= 1024 * 1024 ? (size / 1024 / 1024).toFixed(2) + ' MB' : (size / 1024).toFixed(1) + ' KB'; }
function toast(message) {
  $('toast').textContent = message; $('toast').hidden = false;
  clearTimeout(state.toastTimer); state.toastTimer = setTimeout(() => { $('toast').hidden = true; }, 3200);
}
function showError(message) { $('error-message').textContent = message; $('error-message').hidden = !message; }
function element(tag, className, text) {
  const node = document.createElement(tag); if (className) node.className = className;
  if (text !== undefined) node.textContent = text; return node;
}
function format() { return $('output-format').value; }

async function responseBody(response) {
  let data;
  try { data = await response.json(); } catch (_) { throw new Error('服务返回了无法识别的结果，请检查后端是否已更新'); }
  if (!response.ok) {
    const error = new Error(data.error || '请求失败'); error.status = response.status; throw error;
  }
  return data;
}
async function ensureSession() {
  if (state.token && state.tokenExpires > Date.now()) return;
  if (state.health?.authMode !== 'development') throw new Error('浏览器体验需要本地开发模式；当前服务只接受微信登录');
  if (!state.loginPromise) {
    state.loginPromise = (async () => {
      let deviceId = readStorage('format-factory-web-device', '');
      if (!deviceId || !/^[A-Za-z0-9_-]{16,80}$/.test(deviceId)) {
        const id = crypto.randomUUID ? crypto.randomUUID()
          : Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join('');
        deviceId = 'browser_' + id; store('format-factory-web-device', deviceId);
      }
      const data = await responseBody(await fetch('/api/session', { method: 'POST',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ deviceId }) }));
      state.token = data.token; state.tokenExpires = Date.now() + (data.expiresSeconds - 30) * 1000;
    })().finally(() => { state.loginPromise = null; });
  }
  await state.loginPromise;
}
async function request(path, options = {}, retry = true) {
  await ensureSession();
  let response;
  try { response = await fetch(path, { ...options, headers: { ...options.headers, Authorization: 'Bearer ' + state.token } }); }
  catch (_) { throw new Error('无法连接服务，请确认后端仍在运行'); }
  if (response.status === 401 && retry) { state.token = ''; await ensureSession(); return request(path, options, false); }
  return responseBody(response);
}
function setBusy(busy) {
  state.busy = busy;
  document.querySelectorAll('[data-operation], .settings-panel input, .settings-panel select, #drop-zone, #clear-files, .file-actions button')
    .forEach(node => { node.disabled = busy; });
  $('start-label').textContent = busy ? '正在转换…' : '开始转换'; updateStart();
}
function updateStart() { $('start-button').disabled = state.busy || !state.online || !state.files.length || !$('consent').checked; }
function settings() {
  const operation = state.operation;
  $('format-setting').hidden = !['convertImage', 'pdfToImages'].includes(operation);
  $('quality-setting').hidden = !(operation === 'compress' || (['convertImage', 'pdfToImages'].includes(operation) && ['jpg', 'heic'].includes(format())));
  $('bmp-setting').hidden = operation !== 'convertImage' || !['bmp', 'dib'].includes(format());
  $('width-setting').hidden = operation !== 'compress';
  $('dpi-setting').hidden = operation !== 'pdfToImages';
  $('pdf-setting').hidden = operation !== 'imagesToPdf';
  $('order-hint').hidden = operation !== 'imagesToPdf' || state.files.length < 2;
  $('settings-note').textContent = operation === 'compress' ? '输出为 JPG，透明区域填充白色。转换后体积不保证更小。'
    : operation === 'imagesToPdf' ? '生成 A4 文档，保持图片比例。'
    : ({ jpg: 'JPEG 输出填充白色背景，采用有损压缩。', png: 'PNG 保留图片透明区域，文件体积可能更大。',
        bmp: '位图输出填充白色背景；24 位保留完整颜色。', dib: '位图输出填充白色背景；DIB 部分浏览器无法直接预览。',
        gif: '输出静态 GIF，最多 256 色；半透明效果可能损失。', tiff: '输出单页 TIFF，使用无损压缩。',
        heic: '输出 HEIC，填充白色背景并采用有损压缩；多数浏览器无法直接预览。' })[format()];
}
function resetResult() {
  if (state.resultUrl) URL.revokeObjectURL(state.resultUrl);
  state.resultUrl = null; state.job = null; state.uploadedIds = [];
  $('result-panel').hidden = true; $('progress-panel').hidden = true;
}
function selectTool(operation) {
  if (state.busy || !tools[operation]) return;
  state.operation = operation; clearFiles(); resetResult(); showError('');
  document.querySelectorAll('[data-operation]').forEach(button => {
    const active = button.dataset.operation === operation;
    button.classList.toggle('active', active); button.setAttribute('aria-pressed', String(active));
  });
  $('tool-title').textContent = tools[operation].title; $('tool-description').textContent = tools[operation].description;
  $('file-hint').textContent = tools[operation].hint; $('output-tag').textContent = tools[operation].tag;
  $('file-input').accept = operation === 'pdfToImages' ? '.pdf' : '.jpg,.jpeg,.jpe,.jfif,.png,.bmp,.dib,.gif,.tif,.tiff,.heic';
  $('frame-note').hidden = operation === 'pdfToImages';
  Array.from($('output-format').options).forEach(option => {
    option.hidden = operation === 'pdfToImages' && !['jpg', 'png'].includes(option.value);
    option.disabled = option.hidden || (option.value === 'heic' && state.health?.heicToolsDetected === false);
  });
  if (operation === 'pdfToImages' && !['jpg', 'png'].includes(format())) $('output-format').value = 'jpg';
  $('file-input').multiple = operation === 'imagesToPdf'; settings();
}
function clearFiles() {
  state.files.forEach(item => { if (item.url) URL.revokeObjectURL(item.url); });
  state.files = []; $('file-input').value = ''; renderFiles();
}
function addFiles(selected) {
  if (state.busy) return;
  const incoming = Array.from(selected); if (!incoming.length) return;
  const pdf = state.operation === 'pdfToImages';
  const max = state.operation === 'imagesToPdf' ? 9 : 1;
  if (incoming.some(file => !(pdf ? /\.pdf$/i : /\.(jpg|jpeg|jpe|jfif|png|bmp|dib|gif|tif|tiff|heic)$/i).test(file.name)))
    return showError(pdf ? '请选择 PDF 文件' : '请选择 BMP/DIB、JPEG、GIF、TIFF、PNG 或 HEIC 图片');
  if (incoming.some(file => file.size > MAX_FILE)) return showError('单文件不能超过 20MB，请选择较小的文件');
  if (incoming.some(file => file.size === 0)) return showError('文件不能为空');
  if (incoming.some(file => file.name.length > 160)) return showError('文件名过长，请先缩短文件名');
  if ((max === 9 ? state.files.length : 0) + incoming.length > max)
    return showError(max === 9 ? '图片转 PDF 最多选择 9 张图片' : '此功能每次处理一个文件');
  if (max === 1) clearFiles();
  incoming.forEach(file => state.files.push({ file, url: /\.(jpg|jpeg|jpe|jfif|png|gif|bmp)$/i.test(file.name) ? URL.createObjectURL(file) : null }));
  resetResult(); renderFiles(); showError('');
}
function renderFiles() {
  $('file-list').replaceChildren();
  state.files.forEach((item, index) => {
    const row = element('div', 'file-row');
    if (item.url) {
      const image = element('img', 'file-preview'); image.src = item.url; image.alt = '';
      image.addEventListener('error', () => { image.hidden = true; }); row.append(image);
    } else row.append(element('span', 'file-preview file-symbol', item.file.name.split('.').pop().toUpperCase()));
    const info = element('div', 'file-info'); info.append(element('strong', '', (index + 1) + '. ' + item.file.name), element('small', '', sizeLabel(item.file.size)));
    const actions = element('div', 'file-actions');
    if (state.operation === 'imagesToPdf') {
      [-1, 1].forEach(direction => {
        const move = element('button', '', direction === -1 ? '↑' : '↓');
        move.setAttribute('aria-label', (direction === -1 ? '上移 ' : '下移 ') + item.file.name);
        move.disabled = state.busy || index + direction < 0 || index + direction >= state.files.length;
        move.addEventListener('click', () => {
          const other = index + direction; [state.files[index], state.files[other]] = [state.files[other], state.files[index]]; renderFiles();
        }); actions.append(move);
      });
    }
    const remove = element('button', '', '×'); remove.setAttribute('aria-label', '移除 ' + item.file.name); remove.disabled = state.busy;
    remove.addEventListener('click', () => { if (item.url) URL.revokeObjectURL(item.url); state.files.splice(index, 1); renderFiles(); });
    actions.append(remove); row.append(info, actions); $('file-list').append(row);
  });
  $('file-summary').textContent = state.files.length ? state.files.length + ' 个文件 · 共 ' + sizeLabel(state.files.reduce((sum, item) => sum + item.file.size, 0)) : '尚未选择文件';
  $('clear-files').hidden = !state.files.length; settings(); updateStart();
}
function progress(percent, message) {
  $('progress-panel').hidden = false; $('progress-bar').value = percent;
  $('progress-value').textContent = percent + '%'; $('progress-message').textContent = message;
}
function upload(file, report, retry = true) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest(); xhr.open('POST', '/api/files?name=' + encodeURIComponent(file.name));
    xhr.setRequestHeader('Authorization', 'Bearer ' + state.token);
    xhr.setRequestHeader('Content-Type', 'application/octet-stream'); xhr.timeout = 90000;
    xhr.upload.addEventListener('progress', event => { if (event.lengthComputable) report(event.loaded / event.total); });
    xhr.onload = async () => {
      try {
        if (xhr.status === 401 && retry) { state.token = ''; await ensureSession(); resolve(await upload(file, report, false)); return; }
        const data = JSON.parse(xhr.responseText);
        if (xhr.status < 200 || xhr.status >= 300) throw new Error(data.error || '文件上传失败');
        resolve(data);
      } catch (error) { reject(error); }
    };
    xhr.onerror = () => reject(new Error('文件上传失败，请确认后端仍在运行'));
    xhr.ontimeout = () => reject(new Error('上传超时，请检查网络或选择较小的文件'));
    xhr.send(file);
  });
}
function remember(job) {
  const records = history(); const previous = records.find(item => item.id === job.id);
  const record = { ...previous, ...job, operation: state.operation, title: tools[state.operation].title,
    fileIds: [...state.uploadedIds], originalSize: state.files.reduce((sum, item) => sum + item.file.size, 0), created: previous?.created || Date.now() };
  store('format-factory-web-jobs', [record, ...records.filter(item => item.id !== job.id)].slice(0, 20));
  $('history-count').textContent = history().length;
}
async function start() {
  if (state.busy || !state.files.length || !$('consent').checked) return;
  if (state.operation === 'compress' && !$('max-width').reportValidity()) return;
  resetResult(); showError(''); setBusy(true); progress(0, '准备上传文件');
  try {
    await ensureSession();
    for (let index = 0; index < state.files.length; index++) {
      const file = state.files[index].file;
      const uploaded = await upload(file, fraction => progress(Math.round((index + fraction) * 20 / state.files.length), '上传文件 ' + (index + 1) + '/' + state.files.length));
      state.uploadedIds.push(uploaded.id);
    }
    const payload = { operation: state.operation, fileIds: state.uploadedIds };
    if (['convertImage', 'pdfToImages'].includes(state.operation)) payload.format = format();
    if (state.operation === 'convertImage' && ['bmp', 'dib'].includes(format())) payload.bmpBits = Number($('bmp-bits').value);
    if (state.operation === 'compress') payload.maxWidth = Number($('max-width').value);
    if (state.operation !== 'imagesToPdf') payload.quality = Number($('quality').value);
    if (state.operation === 'pdfToImages') payload.dpi = Number($('dpi').value);
    state.job = await request('/api/jobs', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
    remember(state.job);
    while (true) {
      const job = await request('/api/jobs/' + encodeURIComponent(state.job.id)); state.job = job; remember(job);
      progress(20 + Math.round(job.progress * .8), job.message);
      if (job.state === 'failed') throw new Error(job.message);
      if (job.state === 'done') { showResult(job); break; }
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
  } catch (error) { showError(error.message || '转换失败，请重试'); $('progress-panel').hidden = true; }
  finally { setBusy(false); renderFiles(); renderHistory(); }
}
function showResult(job) {
  $('progress-panel').hidden = true; $('result-panel').hidden = false;
  $('result-name').textContent = job.result.name; $('result-size').textContent = sizeLabel(job.result.size);
  const original = state.files.reduce((sum, item) => sum + item.file.size, 0);
  $('result-comparison').textContent = state.operation !== 'compress' ? '' : job.result.size < original
    ? '体积减少 ' + ((1 - job.result.size / original) * 100).toFixed(1) + '%'
    : '结果比原文件更大，可降低质量后重试。';
}
async function download(result) {
  await ensureSession();
  let response = await fetch('/api/files/' + encodeURIComponent(result.id), { headers: { Authorization: 'Bearer ' + state.token } });
  if (response.status === 401) {
    state.token = ''; await ensureSession();
    response = await fetch('/api/files/' + encodeURIComponent(result.id), { headers: { Authorization: 'Bearer ' + state.token } });
  }
  if (!response.ok) { await responseBody(response); return; }
  const url = URL.createObjectURL(await response.blob());
  const link = element('a'); link.href = url; link.download = result.name; document.body.append(link); link.click(); link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60000); toast('文件下载已开始');
}
async function deleteRecord(record) {
  const ids = [...new Set([...(record.fileIds || []), ...(record.result ? [record.result.id] : [])])];
  for (const id of ids) {
    try { await request('/api/files/' + encodeURIComponent(id), { method: 'DELETE' }); }
    catch (error) { if (error.status !== 404) throw error; }
  }
  store('format-factory-web-jobs', history().filter(job => job.id !== record.id));
  if (state.job?.id === record.id) { resetResult(); toast('原文件和结果已删除'); }
  renderHistory();
}
function renderHistory() {
  const records = history(); $('history-count').textContent = records.length; $('history-list').replaceChildren();
  if (!records.length) { $('history-list').append(element('div', 'empty-history', '还没有任务，选择一个工具开始吧。')); return; }
  const labels = { queued: '等待中', running: '处理中', done: '已完成', failed: '失败', expired: '已过期' };
  records.forEach(record => {
    const row = element('div', 'history-row'); row.append(element('span', 'tool-icon mint', record.operation === 'pdfToImages' ? '▧' : '▤'));
    const info = element('div', 'file-info'); info.append(element('strong', '', record.title), element('small', '', new Date(record.created).toLocaleString('zh-CN') + ' · ' + record.message));
    row.append(info, element('span', 'history-badge ' + record.state, labels[record.state] || '未知'));
    const actions = element('div', 'history-actions');
    if (record.state === 'done' && record.result) {
      const button = element('button', 'text-button', '下载结果 ↓');
      button.addEventListener('click', async () => { button.disabled = true; try { await download(record.result); } catch (error) { showError(error.message); toast(error.message); } finally { button.disabled = false; } }); actions.append(button);
    }
    if (!['queued', 'running'].includes(record.state)) {
      const button = element('button', 'text-button', '删除');
      button.addEventListener('click', async () => { button.disabled = true; try { await deleteRecord(record); } catch (error) { toast(error.message); button.disabled = false; } }); actions.append(button);
    }
    row.append(actions); $('history-list').append(row);
  });
}
async function refreshHistory() {
  $('refresh-history').disabled = true;
  try {
    const records = await Promise.all(history().map(async record => {
      try { return { ...record, ...await request('/api/jobs/' + encodeURIComponent(record.id)) }; }
      catch (error) { return error.status === 404 ? { ...record, state: 'expired', message: '任务已过期或服务已重启' } : record; }
    }));
    store('format-factory-web-jobs', records); renderHistory();
  } catch (error) { toast(error.message); }
  finally { $('refresh-history').disabled = false; }
}
function showView(view) {
  $('workspace-view').hidden = view !== 'workspace'; $('history-view').hidden = view !== 'history';
  document.querySelectorAll('[data-view]').forEach(button => {
    const active = button.dataset.view === view; button.classList.toggle('active', active); button.setAttribute('aria-pressed', String(active));
  });
  if (view === 'history') { renderHistory(); refreshHistory(); }
}
async function connect() {
  try {
    state.health = await responseBody(await fetch('/api/health'));
    if (state.health.application !== 'wechat-format-factory') throw new Error('服务版本不匹配，请重启更新后的后端');
    if (!state.health.imageFormats) throw new Error('图片转换后端已升级，请先停止旧服务并重新运行 run.cmd');
    if (state.health.authMode !== 'development') throw new Error('浏览器体验需要本地开发模式；当前服务只接受微信登录');
    await ensureSession(); state.online = true; selectTool(state.operation);
    $('connection').className = 'connection online'; $('connection-text').textContent = '服务已就绪';
  } catch (error) {
    $('connection').className = 'connection offline'; $('connection-text').textContent = '服务未就绪';
    showError(error.message || '连接失败，请确认后端仍在运行');
  }
  updateStart();
}

document.querySelectorAll('[data-operation]').forEach(button => button.addEventListener('click', () => selectTool(button.dataset.operation)));
document.querySelectorAll('[data-view]').forEach(button => button.addEventListener('click', () => showView(button.dataset.view)));
$('output-format').addEventListener('change', settings);
$('quality').addEventListener('input', () => { $('quality-value').textContent = $('quality').value + '%'; });
$('consent').addEventListener('change', updateStart);
$('drop-zone').addEventListener('click', () => { if (!state.busy) $('file-input').click(); });
$('file-input').addEventListener('change', event => { addFiles(event.target.files); event.target.value = ''; });
['dragenter', 'dragover'].forEach(name => $('drop-zone').addEventListener(name, event => { event.preventDefault(); if (!state.busy) $('drop-zone').classList.add('dragging'); }));
['dragleave', 'drop'].forEach(name => $('drop-zone').addEventListener(name, event => { event.preventDefault(); $('drop-zone').classList.remove('dragging'); }));
$('drop-zone').addEventListener('drop', event => addFiles(event.dataTransfer.files));
document.addEventListener('dragover', event => event.preventDefault());
document.addEventListener('drop', event => event.preventDefault());
$('clear-files').addEventListener('click', () => { clearFiles(); resetResult(); showError(''); });
$('start-button').addEventListener('click', start);
$('refresh-history').addEventListener('click', refreshHistory);
$('download-button').addEventListener('click', async () => {
  $('download-button').disabled = true;
  try { await download(state.job.result); } catch (error) { showError(error.message); }
  finally { $('download-button').disabled = false; }
});
$('delete-button').addEventListener('click', async () => {
  $('delete-button').disabled = true;
  try { const record = history().find(job => job.id === state.job.id); if (record) await deleteRecord(record); }
  catch (error) { showError(error.message); }
  finally { $('delete-button').disabled = false; }
});
window.addEventListener('beforeunload', () => { state.files.forEach(item => { if (item.url) URL.revokeObjectURL(item.url); }); });
renderHistory(); selectTool('compress'); connect();
