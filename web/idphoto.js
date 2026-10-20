'use strict';
const $ = id => document.getElementById(id);
const state = { file: null, fileId: null, ownedIds: new Set(), token: '', ready: false, busy: false,
  sourceUrl: null, resultUrl: null, result: null };

async function body(response) {
  const result = await response.json();
  if (!response.ok) {
    const error = new Error(result.error || '请求失败');
    error.statusCode = response.status;
    throw error;
  }
  return result;
}
async function session() {
  if (state.token) return;
  let device = localStorage.getItem('format-factory-idphoto-device');
  if (!device) {
    device = 'idphoto_' + Array.from(crypto.getRandomValues(new Uint8Array(16)), v => v.toString(16).padStart(2, '0')).join('');
    localStorage.setItem('format-factory-idphoto-device', device);
  }
  state.token = (await body(await fetch('/api/session', { method: 'POST',
    headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ deviceId: device }) }))).token;
}
async function request(url, options = {}) {
  await session();
  const response = await fetch(url, { ...options, headers: { ...options.headers, Authorization: 'Bearer ' + state.token } });
  if (response.status === 401) state.token = '';
  return body(response);
}
function enabled() {
  $('generate').disabled = state.busy || !state.ready || !state.file || !$('consent').checked;
  $('delete').hidden = state.ownedIds.size === 0;
}
function busy(value) {
  state.busy = value;
  document.querySelectorAll('.photo-card input,.photo-card select,#delete,#retry-download').forEach(control => control.disabled = value);
  $('generate').textContent = value ? '正在制作…' : '生成预览';
  enabled();
}
function numbers() {
  const width = Number($('width').value), height = Number($('height').value);
  $('dimensions').textContent = '300 DPI · ' + Math.round(width * 300 / 25.4) + ' × ' + Math.round(height * 300 / 25.4) + ' 像素';
  $('zoom-value').textContent = $('zoom').value + '%';
  $('x-value').textContent = $('offset-x').value;
  $('y-value').textContent = $('offset-y').value;
  if (state.result) $('result-title').textContent = '上一次生成的结果';
}
function clearResult() {
  if (state.resultUrl) URL.revokeObjectURL(state.resultUrl);
  state.resultUrl = null;
  state.result = null;
  $('result-preview').hidden = true;
  $('result-preview').removeAttribute('src');
  $('download').hidden = true;
  $('download').removeAttribute('href');
  $('retry-download').hidden = true;
  $('empty-result').hidden = false;
  $('result-title').textContent = '结果预览';
}
async function downloadResult() {
  $('retry-download').hidden = false;
  const response = await fetch('/api/files/' + state.result.id, { headers: { Authorization: 'Bearer ' + state.token } });
  if (!response.ok) {
    if (response.status === 401) state.token = '';
    throw new Error('预览下载失败，可点击“重试下载预览”或稍后重新生成');
  }
  const resultUrl = URL.createObjectURL(await response.blob());
  if (state.resultUrl) URL.revokeObjectURL(state.resultUrl);
  state.resultUrl = resultUrl;
  $('result-preview').src = resultUrl;
  $('result-preview').hidden = false;
  $('empty-result').hidden = true;
  $('download').href = resultUrl;
  $('download').download = state.result.name;
  $('download').hidden = false;
  $('retry-download').hidden = true;
  $('result-title').textContent = '制作完成';
  $('message').textContent = '照片已生成，可预览或下载';
}

$('preset').addEventListener('change', () => {
  const custom = $('preset').value === 'custom';
  $('custom-size').hidden = !custom;
  if (!custom) {
    const dimensions = $('preset').value.split(',');
    $('width').value = dimensions[0];
    $('height').value = dimensions[1];
  }
  numbers();
});
for (const id of ['width', 'height', 'zoom', 'offset-x', 'offset-y', 'background', 'format']) $(id).addEventListener('input', numbers);
$('consent').addEventListener('change', enabled);
$('photo-file').addEventListener('change', () => {
  const file = $('photo-file').files[0];
  if (!file) return;
  $('error').textContent = '';
  if (file.size > 20 * 1024 * 1024 || !/\.(jpe?g|png|heic)$/i.test(file.name)) {
    $('error').textContent = '请选择不超过 20MB 的 JPG、PNG 或 HEIC 照片';
    return;
  }
  state.file = file;
  state.fileId = null;
  clearResult();
  if (state.sourceUrl) URL.revokeObjectURL(state.sourceUrl);
  state.sourceUrl = URL.createObjectURL(file);
  $('source-preview').src = state.sourceUrl;
  $('source-preview').hidden = /\.heic$/i.test(file.name);
  $('source-name').textContent = file.name + (/\.heic$/i.test(file.name) ? '（浏览器不显示原图，生成后可预览结果）' : '');
  $('zoom').value = '100';
  $('offset-x').value = $('offset-y').value = '0';
  $('message').textContent = '';
  numbers();
  enabled();
});
$('generate').addEventListener('click', async () => {
  if (state.busy || !state.ready || !state.file || !$('consent').checked) return;
  if (!$('width').reportValidity() || !$('height').reportValidity()) return;
  busy(true);
  $('error').textContent = '';
  $('message').textContent = '正在上传照片';
  try {
    if (!state.fileId) {
      state.fileId = (await request('/api/files?name=' + encodeURIComponent(state.file.name), {
        method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: state.file })).id;
      state.ownedIds.add(state.fileId);
    }
    let job = await request('/api/jobs', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ operation: 'idPhoto', fileIds: [state.fileId], format: $('format').value, quality: 95,
        photoBackground: $('background').value, photoWidthMm: Number($('width').value), photoHeightMm: Number($('height').value),
        photoDpi: 300, photoZoom: Number($('zoom').value) / 100, photoOffsetX: Number($('offset-x').value), photoOffsetY: Number($('offset-y').value) }) });
    while (!['done', 'failed'].includes(job.state)) {
      $('message').textContent = job.message;
      await new Promise(resolve => setTimeout(resolve, 1000));
      job = await request('/api/jobs/' + job.id);
    }
    if (job.state === 'failed') throw new Error(job.message);
    state.ownedIds.add(job.result.id);
    clearResult();
    state.result = job.result;
    await downloadResult();
  } catch (error) {
    $('error').textContent = error.message;
    $('message').textContent = '';
  } finally { busy(false); }
});
$('retry-download').addEventListener('click', async () => {
  if (state.busy || !state.result) return;
  busy(true);
  $('error').textContent = '';
  try { await session(); await downloadResult(); }
  catch (error) { $('error').textContent = error.message; }
  finally { busy(false); }
});
$('delete').addEventListener('click', async () => {
  if (state.busy) return;
  busy(true);
  $('error').textContent = '';
  try {
    for (const id of state.ownedIds) {
      try { await request('/api/files/' + id, { method: 'DELETE' }); }
      catch (error) { if (error.statusCode !== 404) throw error; }
      state.ownedIds.delete(id);
      if (state.fileId === id) state.fileId = null;
    }
    clearResult();
    $('message').textContent = '本页上传的照片和生成结果已从服务器删除';
  } catch (error) { $('error').textContent = error.message; }
  finally { busy(false); }
});
fetch('/api/health').then(body).then(health => {
  state.ready = health.authMode === 'development' && health.idPhotoEnabled;
  $('status').textContent = state.ready ? '本地证件照服务已就绪' : health.authMode !== 'development' ? '浏览器体验仅用于本地调试' : '本地证件照模型尚未配置';
  enabled();
}).catch(() => { $('status').textContent = '服务未连接，请启动后端'; });
