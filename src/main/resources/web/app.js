const $ = (selector) => document.querySelector(selector);
const state = {
  anchors: [],
  sessions: [],
  logs: [],
  proxy: null,
  settings: null,
  monitorRunning: false,
};

async function api(path, options = {}) {
  const request = { ...options, headers: { ...(options.headers || {}) } };
  if (request.body && typeof request.body !== 'string') {
    request.headers['Content-Type'] = 'application/json';
    request.body = JSON.stringify(request.body);
  }
  const response = await fetch(path, request);
  const text = await response.text();
  let payload = null;
  if (text) {
    try { payload = JSON.parse(text); } catch { payload = { error: text }; }
  }
  if (!response.ok) {
    throw new Error(payload?.error || `请求失败 (${response.status})`);
  }
  return payload;
}

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function formatDate(value) {
  if (!value) return '--';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '--';
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
    hour12: false,
  }).format(date);
}

function formatDuration(session) {
  if (!session.endTime) return '直播中';
  const seconds = Number(session.durationSeconds || 0);
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const remain = seconds % 60;
  if (hours) return `${hours}小时${minutes}分${remain}秒`;
  if (minutes) return `${minutes}分${remain}秒`;
  return `${remain}秒`;
}

function statusInfo(status) {
  if (status === 'LIVE') return { className: 'status-live', label: '直播中' };
  if (status === 'OFFLINE') return { className: 'status-offline', label: '未开播' };
  return { className: 'status-unknown', label: '未知' };
}

let toastTimer;
function toast(message, error = false) {
  const node = $('#toast');
  node.textContent = message;
  node.classList.toggle('error', error);
  node.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => node.classList.remove('show'), 3200);
}

function emptyRow(columns, text) {
  return `<tr><td class="empty" colspan="${columns}">${escapeHtml(text)}</td></tr>`;
}

function setMonitorState(running) {
  state.monitorRunning = running;
  const pill = $('#monitor-state');
  pill.textContent = running ? '监控运行中' : '监控已停止';
  pill.className = `pill ${running ? 'pill-on' : 'pill-off'}`;
  $('#btn-start').disabled = running;
  $('#btn-stop').disabled = !running;
}

function renderOverview(overview) {
  state.settings = overview.settings;
  state.monitorRunning = overview.monitorRunning;
  state.proxy = overview.proxy;
  setMonitorState(overview.monitorRunning);
  $('#stat-anchors').textContent = overview.anchorCount;
  $('#stat-live').textContent = overview.liveAnchorCount;
  $('#stat-enabled').textContent = overview.enabledAnchorCount;

  const proxy = overview.proxy || {};
  $('#stat-proxy').textContent = proxy.enabled ? `${proxy.available ?? 0}` : '未启用';
  $('#stat-proxy-detail').textContent = proxy.enabled
    ? `候选 ${proxy.candidateCount ?? 0} · ${proxy.lastRefresh ? formatDate(proxy.lastRefresh) : '尚未刷新'}`
    : '直连模式';
}

function renderAnchors() {
  const body = $('#anchors-body');
  if (!state.anchors.length) {
    body.innerHTML = emptyRow(6, '还没有主播，点击右上角新增。');
    return;
  }
  body.innerHTML = state.anchors.map((anchor) => `
    <tr>
      <td><strong>${escapeHtml(anchor.nickname)}</strong></td>
      <td>${escapeHtml(anchor.douyinId)}</td>
      <td>${escapeHtml(anchor.webRid || '自动反查')}</td>
      <td class="muted">${escapeHtml(anchor.remark || '--')}</td>
      <td>${anchor.enabled ? '是' : '否'}</td>
      <td>
        <button class="link-button" type="button" data-edit-anchor="${anchor.id}">编辑</button>
        <button class="link-button danger" type="button" data-delete-anchor="${anchor.id}">删除</button>
      </td>
    </tr>
  `).join('');
}

function renderStatus() {
  const body = $('#status-body');
  if (!state.anchors.length) {
    body.innerHTML = emptyRow(5, '暂无主播。');
    return;
  }
  body.innerHTML = state.anchors.map((anchor) => {
    const info = statusInfo(anchor.lastStatus);
    return `
      <tr>
        <td><span class="status-tag ${info.className}"><span class="status-dot"></span>${info.label}</span></td>
        <td><strong>${escapeHtml(anchor.nickname)}</strong></td>
        <td>${escapeHtml(anchor.douyinId)}</td>
        <td>${escapeHtml(anchor.webRid || '--')}</td>
        <td class="muted">${formatDate(anchor.lastCheckTime)}</td>
      </tr>
    `;
  }).join('');
}

function renderSessions() {
  const body = $('#sessions-body');
  if (!state.sessions.length) {
    body.innerHTML = emptyRow(4, '暂无开播记录。');
    return;
  }
  body.innerHTML = state.sessions.map((session) => `
    <tr>
      <td><strong>${escapeHtml(session.nickname || '未命名')}</strong></td>
      <td>${formatDate(session.startTime)}</td>
      <td>${session.endTime ? formatDate(session.endTime) : '<span class="status-live">直播中</span>'}</td>
      <td>${escapeHtml(formatDuration(session))}</td>
    </tr>
  `).join('');
}

function renderLogs() {
  const body = $('#logs-body');
  if (!state.logs.length) {
    body.innerHTML = emptyRow(4, '暂无日志。');
    return;
  }
  body.innerHTML = state.logs.map((entry) => `
    <tr>
      <td class="muted">${formatDate(entry.createTime)}</td>
      <td><span class="level level-${escapeHtml(entry.level)}">${escapeHtml(entry.level)}</span></td>
      <td>${escapeHtml(entry.source || '--')}</td>
      <td>${escapeHtml(entry.message || '')}</td>
    </tr>
  `).join('');
}

function renderProxy(proxy) {
  state.proxy = proxy;
  const summary = proxy.enabled
    ? `可用 ${proxy.available} 个，候选 ${proxy.candidateCount} 个${proxy.validationTotal ? `，验证 ${proxy.validationDone}/${proxy.validationTotal}` : ''}。`
    : '代理池未启用，当前使用直连。';
  $('#proxy-summary').textContent = summary;
  $('#stat-proxy').textContent = proxy.enabled ? proxy.available : '未启用';
  $('#stat-proxy-detail').textContent = proxy.lastRefresh
    ? `最近刷新 ${formatDate(proxy.lastRefresh)}`
    : '尚未刷新';

  const events = [...(proxy.events || [])].reverse();
  $('#proxy-events').innerHTML = events.length
    ? events.map((event) => `<li>${escapeHtml(event)}</li>`).join('')
    : '<li class="muted">暂无事件</li>';

  const proxies = proxy.proxies || [];
  $('#proxy-list').innerHTML = proxies.length
    ? proxies.map((item) => `<div class="proxy-item"><span>${escapeHtml(item.host)}:${item.port}</span><span>${escapeHtml(item.type)}</span></div>`).join('')
    : '<p class="muted">当前没有可用代理，将自动直连。</p>';
}

async function loadData(showError = false) {
  try {
    const [overview, anchors, sessions, logs, proxy] = await Promise.all([
      api('/api/overview'),
      api('/api/anchors'),
      api('/api/sessions?limit=200'),
      api('/api/logs?limit=200'),
      api('/api/proxy'),
    ]);
    state.anchors = anchors;
    state.sessions = sessions;
    state.logs = logs;
    renderOverview(overview);
    renderProxy(proxy);
    renderAnchors();
    renderStatus();
    renderSessions();
    renderLogs();
  } catch (error) {
    if (showError) toast(error.message, true);
    console.error(error);
  }
}

function openAnchorDialog(id = null) {
  const anchor = id ? state.anchors.find((item) => Number(item.id) === Number(id)) : null;
  $('#anchor-dialog-title').textContent = anchor ? '编辑主播' : '新增主播';
  $('#anchor-id').value = anchor?.id ?? '';
  $('#anchor-nickname').value = anchor?.nickname ?? '';
  $('#anchor-douyin-id').value = anchor?.douyinId ?? '';
  $('#anchor-web-rid').value = anchor?.webRid ?? '';
  $('#anchor-home-url').value = anchor?.homeUrl ?? '';
  $('#anchor-remark').value = anchor?.remark ?? '';
  $('#anchor-enabled').checked = anchor ? Boolean(anchor.enabled) : true;
  $('#anchor-dialog').showModal();
  setTimeout(() => $('#anchor-nickname').focus(), 0);
}

function openSettingsDialog() {
  const settings = state.settings || {};
  $('#setting-interval').value = settings.monitorIntervalSeconds ?? 30;
  $('#setting-alert').checked = Boolean(settings.alertEnabled);
  $('#setting-log').checked = Boolean(settings.logEnabled);
  $('#settings-dialog').showModal();
}

function bindEvents() {
  document.querySelectorAll('.tab').forEach((tab) => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.tab').forEach((item) => item.classList.toggle('active', item === tab));
      document.querySelectorAll('.panel').forEach((panel) => panel.classList.remove('active'));
      $(`#panel-${tab.dataset.tab}`).classList.add('active');
    });
  });

  document.querySelectorAll('[data-close-dialog]').forEach((button) => {
    button.addEventListener('click', () => button.closest('dialog').close());
  });

  $('#btn-start').addEventListener('click', async () => {
    try { await api('/api/monitor/start', { method: 'POST' }); setMonitorState(true); toast('监控已启动'); }
    catch (error) { toast(error.message, true); }
  });
  $('#btn-stop').addEventListener('click', async () => {
    try { await api('/api/monitor/stop', { method: 'POST' }); setMonitorState(false); toast('监控已停止'); }
    catch (error) { toast(error.message, true); }
  });
  $('#btn-settings').addEventListener('click', openSettingsDialog);
  $('#btn-add-anchor').addEventListener('click', () => openAnchorDialog());
  $('#btn-refresh-status').addEventListener('click', () => loadData(true));
  $('#btn-refresh-sessions').addEventListener('click', () => loadData(true));
  $('#btn-refresh-logs').addEventListener('click', () => loadData(true));

  $('#btn-clear-logs').addEventListener('click', async () => {
    if (!confirm('确定清空数据库中的运行日志吗？')) return;
    try { await api('/api/logs', { method: 'DELETE' }); state.logs = []; renderLogs(); toast('日志已清空'); }
    catch (error) { toast(error.message, true); }
  });

  $('#btn-refresh-proxy').addEventListener('click', async () => {
    try {
      const result = await api('/api/proxy/refresh', { method: 'POST' });
      toast(result.accepted ? '已开始刷新代理池' : '代理池正在刷新或未启用');
    } catch (error) { toast(error.message, true); }
  });

  $('#anchors-body').addEventListener('click', async (event) => {
    const editId = event.target.dataset.editAnchor;
    if (editId) { openAnchorDialog(editId); return; }
    const deleteId = event.target.dataset.deleteAnchor;
    if (!deleteId) return;
    const anchor = state.anchors.find((item) => Number(item.id) === Number(deleteId));
    if (!anchor || !confirm(`确定删除主播“${anchor.nickname}”吗？`)) return;
    try { await api(`/api/anchors/${deleteId}`, { method: 'DELETE' }); await loadData(); toast('主播已删除'); }
    catch (error) { toast(error.message, true); }
  });

  $('#anchor-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const id = $('#anchor-id').value;
    const payload = {
      nickname: $('#anchor-nickname').value.trim(),
      douyinId: $('#anchor-douyin-id').value.trim(),
      webRid: $('#anchor-web-rid').value.trim() || null,
      homeUrl: $('#anchor-home-url').value.trim() || null,
      remark: $('#anchor-remark').value.trim() || null,
      enabled: $('#anchor-enabled').checked,
    };
    try {
      await api(id ? `/api/anchors/${id}` : '/api/anchors', {
        method: id ? 'PUT' : 'POST',
        body: payload,
      });
      $('#anchor-dialog').close();
      await loadData();
      toast(id ? '主播信息已更新' : '主播已新增');
    } catch (error) { toast(error.message, true); }
  });

  $('#settings-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    try {
      state.settings = await api('/api/settings', {
        method: 'PUT',
        body: {
          monitorIntervalSeconds: Number($('#setting-interval').value),
          alertEnabled: $('#setting-alert').checked,
          logEnabled: $('#setting-log').checked,
        },
      });
      $('#settings-dialog').close();
      await loadData();
      toast('设置已保存');
    } catch (error) { toast(error.message, true); }
  });
}

bindEvents();
loadData(true);
setInterval(() => {
  if (document.visibilityState === 'visible') loadData(false);
}, 5000);
