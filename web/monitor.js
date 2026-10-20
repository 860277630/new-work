'use strict';
const $ = id => document.getElementById(id);
const fields = { cpuPercent: 'setting-cpu', memoryPercent: 'setting-memory', heapPercent: 'setting-heap', intervalSeconds: 'setting-interval', consecutiveSamples: 'setting-count' };
let token = '', timer, controller, snapshot, dirty = false, saving = false, requestVersion = 0;
function percent(value) { return Number.isFinite(value) ? value.toFixed(1) + '%' : '—'; }
function bytes(value) { return Number.isFinite(value) ? (value / 1024 / 1024).toFixed(1) + ' MB' : '—'; }
function time(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'; }
function node(tag, text, className) { const element = document.createElement(tag); element.textContent = text; if (className) element.className = className; return element; }
function notice(message) { $('notice').textContent = message; $('notice').hidden = !message; }
function connection(label, state) { $('status').textContent = label; $('status').className = 'badge ' + state; }
async function api(path, options = {}) {
  controller = new AbortController(); const current = controller;
  const timeout = setTimeout(() => current.abort(), 8000);
  try {
    const response = await fetch(path, { ...options, signal: current.signal, cache: 'no-store', headers: {
      ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: 'Bearer ' + token } : {}) } });
    const data = await response.json();
    if (!response.ok) { const error = new Error(data.error || '监控请求失败'); error.status = response.status; throw error; }
    return data;
  } finally { clearTimeout(timeout); if (controller === current) controller = null; }
}
function svg(tag, attributes, text) {
  const element = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [key, value] of Object.entries(attributes)) element.setAttribute(key, String(value));
  if (text !== undefined) element.textContent = text; return element;
}
function trend(data) {
  const plot = $('trend'); plot.replaceChildren(); const samples = data.samples || [];
  const x = index => 42 + index * 700 / Math.max(1, samples.length - 1), y = value => 215 - value * 1.8;
  for (const value of [0, 25, 50, 75, 100]) {
    plot.append(svg('line', { x1: 42, y1: y(value), x2: 742, y2: y(value), class: 'grid' }));
    plot.append(svg('text', { x: 32, y: y(value) + 4, 'text-anchor': 'end' }, value + '%'));
  }
  for (const metric of ['cpu', 'memory', 'heap']) {
    const threshold = data.settings[metric + 'Percent'];
    plot.append(svg('line', { x1: 42, y1: y(threshold), x2: 742, y2: y(threshold), class: 'threshold ' + metric + '-line' }));
    let path = '', connected = false;
    samples.forEach((sample, index) => {
      const value = sample[metric + 'Percent'];
      if (!Number.isFinite(value)) { connected = false; return; }
      path += (connected ? ' L' : ' M') + x(index).toFixed(2) + ' ' + y(value).toFixed(2); connected = true;
    });
    if (path) plot.append(svg('path', { d: path, class: 'line ' + metric + '-line' }));
  }
  if (samples.length) {
    const label = sample => new Date(sample.at).toLocaleTimeString('zh-CN', { hour12: false });
    plot.append(svg('text', { x: 42, y: 245 }, label(samples[0])));
    plot.append(svg('text', { x: 742, y: 245, 'text-anchor': 'end' }, label(samples[samples.length - 1])));
  }
  $('chart-caption').textContent = '展示最近 ' + samples.length + ' 次采样；每 ' + data.settings.intervalSeconds + ' 秒采样，页面自动刷新。采集失败的数据留空。';
}
function render(data) {
  snapshot = data; const latest = data.samplingError ? {} : data.latest || {}, config = data.settings;
  for (const metric of ['cpu', 'memory', 'heap']) {
    const rule = data.rules.find(item => item.metric === metric), value = latest[metric + 'Percent'];
    $(metric + '-value').textContent = percent(value); $(metric + '-bar').value = Number.isFinite(value) ? value : 0;
    $(metric + '-card').className = 'panel metric' + (rule.active ? ' alarm' : rule.consecutive ? ' observing' : '');
    $(metric + '-state').textContent = !Number.isFinite(value) ? '暂无有效采样' : rule.active ? '告警中' : rule.consecutive ? '观察中 ' + rule.consecutive + '/' + config.consecutiveSamples : '正常';
  }
  $('cpu-detail').textContent = '告警阈值 ' + config.cpuPercent + '% · 可用处理器 ' + (latest.processors ?? '—');
  $('memory-detail').textContent = bytes(latest.memoryUsedBytes) + ' / ' + bytes(latest.memoryTotalBytes) + ' · 阈值 ' + config.memoryPercent + '%';
  $('heap-detail').textContent = bytes(latest.heapUsedBytes) + ' / ' + bytes(latest.heapMaxBytes) + ' · 阈值 ' + config.heapPercent + '%';
  $('updated-at').textContent = '最近采样：' + time(data.latest?.at);
  $('process-cpu').textContent = 'Java 进程 CPU：' + percent(latest.processCpuPercent);
  $('jobs').textContent = '转换任务：运行 ' + (latest.runningJobs ?? '—') + ' / 排队 ' + (latest.queuedJobs ?? '—');
  $('uptime').textContent = '运行时间：' + (Number.isFinite(latest.uptimeSeconds) ? Math.floor(latest.uptimeSeconds / 60) + ' 分钟' : '—');
  if (!dirty) for (const [key, id] of Object.entries(fields)) $(id).value = config[key];
  for (const id of Object.values(fields)) $(id).disabled = saving;
  $('save-settings').disabled = saving; $('export-samples').disabled = !data.samples.length;
  $('rule-note').textContent = '连续 ' + config.consecutiveSamples + ' 次达到阈值时告警；持续超限每 ' + config.cooldownSeconds + ' 秒提醒，低于阈值 5 个百分点时恢复。修改设置后重新开始计数。';
  $('log-directory').textContent = '日志目录：' + data.logDirectory;
  $('retention-note').textContent = '采样和告警写入 JSONL；日志保留 ' + config.retentionDays + ' 天，按 UTC 日期及单文件 ' + config.maxLogMegabytes + ' MB 轮转。';
  $('memory-source').textContent = '内存统计来源：' + ({ 'jdk-operating-environment': 'JVM 所在操作环境的总内存与空闲内存', 'linux-memavailable': 'Linux MemAvailable（可回收缓存计为可用）', 'cgroup-v2': '容器 cgroup v2 用量与限制，包含缓存及子进程' }[latest.memorySource] || '等待采样');
  const labels = { ALERT: '超限告警', REMINDER: '持续超限', RECOVERED: '已恢复', CONFIG_CHANGED: '设置变更' };
  const metrics = { cpu: 'CPU', memory: '系统 / 容器内存', heap: 'Java 堆内存', configuration: '监控设置' };
  $('event-list').replaceChildren();
  [...data.events].reverse().forEach(event => {
    const row = document.createElement('tr'), type = document.createElement('td');
    type.append(node('span', labels[event.type] || event.type, 'event-type ' + event.type));
    row.append(node('td', time(event.at)), type, node('td', metrics[event.metric] || event.metric), node('td', percent(event.value) + ' / ' + percent(event.threshold)), node('td', event.message));
    $('event-list').append(row);
  });
  $('event-count').textContent = data.events.length + ' 条'; $('empty-events').hidden = !!data.events.length;
  trend(data);
  const error = [data.samplingError, data.recordingError].filter(Boolean).join('；'); notice(error);
  connection(error ? '监控异常' : data.status === 'alerting' ? '资源超限' : '监控正常', error ? 'error' : data.status);
}
function schedule() { clearTimeout(timer); if (!document.hidden) timer = setTimeout(load, (snapshot?.settings.intervalSeconds || 5) * 1000); }
async function load() {
  clearTimeout(timer); const version = ++requestVersion;
  try { const data = await api('/api/admin/metrics?limit=120'); if (version === requestVersion) render(data); }
  catch (error) {
    if (version !== requestVersion) return;
    connection(error.status === 403 ? '需要管理员授权' : '连接失败', 'error');
    notice(error.message || '无法读取监控数据，稍后自动重试');
    if (error.status === 403) return;
  }
  if (version === requestVersion) schedule();
}
$('access-form').addEventListener('submit', event => { event.preventDefault(); token = $('monitor-token').value.trim(); $('monitor-token').value = ''; controller?.abort(); load(); });
Object.values(fields).forEach(id => $(id).addEventListener('input', () => { dirty = true; $('save-result').textContent = ''; }));
$('settings-form').addEventListener('submit', async event => {
  event.preventDefault(); if (saving) return;
  const body = Object.fromEntries(Object.entries(fields).map(([key, id]) => [key, Number($(id).value)]));
  saving = true; clearTimeout(timer); ++requestVersion; controller?.abort();
  $('save-settings').disabled = true;
  for (const id of Object.values(fields)) $(id).disabled = true;
  try { const data = await api('/api/admin/monitor-settings', { method: 'PUT', body: JSON.stringify(body) }); dirty = false; render(data); $('save-result').textContent = '已保存并应用，重启后继续使用（环境变量可覆盖）。'; }
  catch (error) { notice(error.message || '保存失败'); $('save-result').textContent = '未保存，请检查输入和连接。'; }
  finally { saving = false; $('save-settings').disabled = false; for (const id of Object.values(fields)) $(id).disabled = false; schedule(); }
});
$('export-samples').addEventListener('click', () => {
  if (!snapshot) return;
  const keys = ['at', 'cpuPercent', 'processCpuPercent', 'memoryPercent', 'memoryUsedBytes', 'memoryTotalBytes', 'heapPercent', 'heapUsedBytes', 'heapMaxBytes', 'runningJobs', 'queuedJobs'];
  const csv = '\uFEFF' + [keys.join(','), ...snapshot.samples.map(sample => keys.map(key => sample[key] ?? '').join(','))].join('\r\n');
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a'); link.href = url; link.download = 'resource-samples.csv'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
});
document.addEventListener('visibilitychange', () => { if (document.hidden) { clearTimeout(timer); ++requestVersion; controller?.abort(); } else if (!saving) load(); });
window.addEventListener('beforeunload', () => { clearTimeout(timer); controller?.abort(); });
load();
