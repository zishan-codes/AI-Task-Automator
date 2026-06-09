// ════════════════════════════════════════════════════════
//  AgentCore — Frontend Simulation Engine
//  Extracted from index.html into standalone script.js
// ════════════════════════════════════════════════════════

'use strict';

// ── Constants ─────────────────────────────────────────

const TASK_TYPES = {
  backup:   { name: 'System Backup',         dur: [1200, 2400], icon: '💾' },
  sync:     { name: 'Database Sync',          dur: [900,  1800], icon: '🔄' },
  report:   { name: 'Analytics Report',       dur: [1400, 2800], icon: '📊' },
  health:   { name: 'Server Health Monitor',  dur: [600,  1200], icon: '🩺' },
  security: { name: 'Security Scan',          dur: [1600, 3200], icon: '🔒' },
};

const NLP_MAP = [
  [/backup|back up/i,                      'backup'],
  [/sync|database|db/i,                    'sync'],
  [/report|analytics|analyze|system/i,     'report'],
  [/health|server/i,                       'health'],
  [/security|scan/i,                       'security'],
  [/history|log/i,                         'HISTORY'],
  [/status|active/i,                       'STATUS'],
  [/help/i,                                'HELP'],
  [/^(exit|quit)$/i,                       'EXIT'],
];

const FAILURE_MSGS = [
  'Network failure — host unreachable',
  'Server timeout — response exceeded 30 s',
  'Database unavailable — connection pool exhausted',
];

const THREAD_NAMES = ['AgentWorker-1', 'AgentWorker-2', 'AgentWorker-3', 'AgentWorker-4'];
const REASONING_STEPS = [
  'Parsing natural language intent…',
  'Selecting optimal task handler…',
  'Allocating worker thread…',
  'Initialising task lifecycle…',
];

// ── State ─────────────────────────────────────────────

const state = {
  tasks:         [],     // all task records
  taskCounter:   1,
  activeThreads: 0,
  stats: { total: 0, success: 0, failed: 0 },
  startTime:     Date.now(),
};

// ── DOM refs ──────────────────────────────────────────

const $ = id => document.getElementById(id);

const chatLog         = $('chatLog');
const activityLog     = $('activityLog');
const errorLog        = $('errorLog');
const taskTableBody   = $('taskTableBody');
const cmdInput        = $('cmdInput');
const thinkingOverlay = $('thinkingOverlay');
const thinkingText    = $('thinkingText');

// ── Helpers ───────────────────────────────────────────

function rand(min, max) { return Math.floor(Math.random() * (max - min + 1)) + min; }
function sleep(ms)      { return new Promise(r => setTimeout(r, ms)); }
function taskId()       { return 'TASK-' + String(state.taskCounter++).padStart(4, '0'); }
function threadName()   { return THREAD_NAMES[rand(0, THREAD_NAMES.length - 1)]; }
function ts()           { return new Date().toLocaleTimeString('en-GB', { hour12: false }); }

// ── Clock & Uptime ────────────────────────────────────

function formatHMS(ms) {
  const s   = Math.floor(ms / 1000);
  const h   = Math.floor(s / 3600);
  const m   = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  return [h, m, sec].map(v => String(v).padStart(2, '0')).join(':');
}

setInterval(() => {
  $('liveClock').textContent     = new Date().toLocaleTimeString('en-GB', { hour12: false });
  $('uptimeCounter').textContent = formatHMS(Date.now() - state.startTime);
}, 1000);

// ── Chat Messages ─────────────────────────────────────

function addChatMsg(text, type = 'agent') {
  const icons = { agent: '⬡', user: '›', error: '✕', info: '·' };
  const div = document.createElement('div');
  div.className = `chat-msg ${type}`;
  div.innerHTML = `<span class="msg-icon">${icons[type] || '·'}</span>
                   <span class="msg-text">${text}</span>`;
  chatLog.appendChild(div);
  chatLog.scrollTop = chatLog.scrollHeight;
}

function addActivityEntry(text, level = 'info') {
  const labels = {
    info: 'INFO', task: 'TASK', success: 'SUCCESS',
    error: 'ERROR', thread: 'THREAD', warning: 'WARNING',
  };
  const div = document.createElement('div');
  div.className = `activity-entry ${level}`;
  div.innerHTML = `<span class="act-time">${ts()}</span>
                   <span class="act-badge ${level}">${labels[level] || 'INFO'}</span>
                   <span class="act-msg">${text}</span>`;
  activityLog.prepend(div);
  // cap at 80 entries to avoid memory bloat
  while (activityLog.children.length > 80) activityLog.removeChild(activityLog.lastChild);
}

function addErrorEntry(text) {
  const empty = errorLog.querySelector('.error-empty');
  if (empty) empty.remove();

  const div = document.createElement('div');
  div.className = 'error-entry';
  div.innerHTML = `<span class="err-time">${ts()}</span>
                   <span class="err-msg">${text}</span>`;
  errorLog.prepend(div);
}

// ── Stats Cards ───────────────────────────────────────

function updateStats() {
  $('statTotal').textContent   = state.stats.total;
  $('statSuccess').textContent = state.stats.success;
  $('statFailed').textContent  = state.stats.failed;
  $('statThreads').textContent = state.activeThreads;
}

function bumpStat(key) {
  state.stats[key]++;
  state.stats.total = state.stats.success + state.stats.failed;
  updateStats();
}

// ── Task Table ────────────────────────────────────────

function upsertTaskRow(task) {
  // remove the "no tasks yet" placeholder on first insert
  const empty = taskTableBody.querySelector('.table-empty');
  if (empty) empty.remove();

  let row = document.getElementById('row-' + task.id);
  if (!row) {
    row = document.createElement('tr');
    row.id = 'row-' + task.id;
    taskTableBody.prepend(row);
  }

  const dur = task.endTime && task.startTime
    ? ((task.endTime - task.startTime) / 1000).toFixed(2) + ' s'
    : task.status === 'RUNNING' ? '…' : '—';

  row.className = `task-row status-${task.status.toLowerCase()}`;
  row.innerHTML = `
    <td class="mono">${task.id}</td>
    <td>${task.icon} ${task.name}</td>
    <td>
      <span class="status-badge ${task.status.toLowerCase()}">${task.status}</span>
      ${task.progress !== undefined && task.status === 'RUNNING'
        ? `<div class="progress-bar"><div class="progress-fill" style="width:${task.progress}%"></div></div>`
        : ''}
    </td>
    <td class="mono">${dur}</td>
    <td class="mono thread-name">${task.thread || '—'}</td>
  `;
}

// ── Progress Updater ──────────────────────────────────

async function animateProgress(task, totalMs) {
  const step  = 100;
  const steps = totalMs / step;
  for (let i = 0; i <= steps; i++) {
    if (task.status !== 'RUNNING') break;
    task.progress = Math.min(Math.round((i / steps) * 100), 99);
    upsertTaskRow(task);
    await sleep(step);
  }
}

// ── AI Thinking Animation ─────────────────────────────

async function showThinking() {
  thinkingOverlay.classList.add('active');
  for (const step of REASONING_STEPS) {
    thinkingText.textContent = step;
    await sleep(380);
  }
  thinkingOverlay.classList.remove('active');
}

// ── NLP Parser ────────────────────────────────────────

function parseCommand(input) {
  const str = input.trim();
  for (const [re, type] of NLP_MAP) {
    if (re.test(str)) return type;
  }
  throw new Error(`InvalidAgentCommandException: unrecognized command "${input}"`);
}

// ── Special Commands ──────────────────────────────────

function showHistory() {
  if (!state.tasks.length) {
    addChatMsg('No task history found. Dispatch a task first.', 'info');
    return;
  }
  const lines = state.tasks.map(t => {
    const dur = t.endTime && t.startTime
      ? ((t.endTime - t.startTime) / 1000).toFixed(2) + 's'
      : '—';
    return `<span class="mono">${t.id}</span> · ${t.icon} ${t.name} · ` +
           `<span class="status-inline ${t.status.toLowerCase()}">${t.status}</span> · ${dur}`;
  }).reverse().join('<br>');
  addChatMsg(`<strong>Task History (${state.tasks.length} records)</strong><br>${lines}`, 'agent');
}

function showStatus() {
  const uptime = formatHMS(Date.now() - state.startTime);
  addChatMsg(
    `<strong>Agent Status</strong><br>` +
    `Uptime: <span class="mono">${uptime}</span> · ` +
    `Total: <b>${state.stats.total}</b> · ` +
    `Success: <b class="green">${state.stats.success}</b> · ` +
    `Failed: <b class="red">${state.stats.failed}</b> · ` +
    `Active threads: <b class="blue">${state.activeThreads}</b>`,
    'agent'
  );
}

function showHelp() {
  addChatMsg(
    `<strong>Available Commands</strong><br>` +
    `<span class="mono">schedule backup</span> — run system backup<br>` +
    `<span class="mono">generate report</span> — generate analytics report<br>` +
    `<span class="mono">sync database</span> — synchronise database<br>` +
    `<span class="mono">analyze system</span> — system analysis report<br>` +
    `<span class="mono">check server health</span> — health monitor<br>` +
    `<span class="mono">run security scan</span> — security audit<br>` +
    `<span class="mono">show task history</span> — execution history<br>` +
    `<span class="mono">status</span> — live agent metrics<br>` +
    `<span class="mono">help</span> — this menu`,
    'agent'
  );
}

// ── Task Dispatcher ───────────────────────────────────

async function dispatchTask(cmdInput_raw, taskType) {
  const meta = TASK_TYPES[taskType];
  const id   = taskId();
  const thr  = threadName();
  const dur  = rand(...meta.dur);

  const task = {
    id,
    name:      meta.name,
    icon:      meta.icon,
    type:      taskType,
    status:    'PENDING',
    thread:    thr,
    startTime: null,
    endTime:   null,
    progress:  0,
  };
  state.tasks.push(task);
  upsertTaskRow(task);

  // — Reasoning phase —
  await showThinking();
  addActivityEntry(`Intent detected: "${cmdInput_raw}"`, 'info');
  addActivityEntry(`Task selected: ${meta.name}  [${id}]`, 'task');
  addActivityEntry(`Priority: NORMAL · Worker: ${thr}`, 'thread');

  // — Queue —
  task.status = 'QUEUED';
  upsertTaskRow(task);
  addActivityEntry(`${meta.name} [${id}] → QUEUED`, 'task');
  addChatMsg(`Queued: ${meta.icon} <b>${meta.name}</b> <span class="mono">[${id}]</span> on ${thr}`, 'agent');
  await sleep(200);

  // — Running —
  task.status    = 'RUNNING';
  task.startTime = Date.now();
  state.activeThreads++;
  updateStats();
  addActivityEntry(`${meta.name} [${id}] → RUNNING on ${thr}`, 'task');
  upsertTaskRow(task);
  animateProgress(task, dur);  // fire-and-forget progress bar

  await sleep(dur);

  // — Failure simulation (~25 %) —
  const failRoll = Math.random();
  if (failRoll < 0.25) {
    const reason = FAILURE_MSGS[rand(0, FAILURE_MSGS.length - 1)];
    addActivityEntry(`[ERROR] ${meta.name}: ${reason}`, 'error');
    addErrorEntry(`${meta.name} [${id}]: ${reason}`);
    addChatMsg(`⚠ Error: ${reason} — initiating auto-retry…`, 'error');

    // — Retry —
    await sleep(600);
    addActivityEntry(`Retrying ${meta.name} [${id}]…`, 'warning');
    await sleep(dur * 0.75);

    // retry succeeds ~80 % of the time
    if (Math.random() < 0.8) {
      task.status   = 'COMPLETED';
      task.endTime  = Date.now();
      task.progress = 100;
      bumpStat('success');
      addActivityEntry(`${meta.name} [${id}] → COMPLETED (retry)`, 'success');
      addChatMsg(`✓ ${meta.icon} <b>${meta.name}</b> completed after retry.`, 'agent');
    } else {
      task.status   = 'FAILED';
      task.endTime  = Date.now();
      task.progress = 0;
      bumpStat('failed');
      addActivityEntry(`${meta.name} [${id}] → FAILED after retry`, 'error');
      addChatMsg(`✕ ${meta.icon} <b>${meta.name}</b> failed. Check Error Center.`, 'error');
    }
  } else {
    task.status   = 'COMPLETED';
    task.endTime  = Date.now();
    task.progress = 100;
    bumpStat('success');
    addActivityEntry(
      `${meta.name} [${id}] → COMPLETED in ${((task.endTime - task.startTime) / 1000).toFixed(2)} s`,
      'success'
    );
    addChatMsg(`✓ ${meta.icon} <b>${meta.name}</b> completed successfully.`, 'agent');
  }

  state.activeThreads = Math.max(0, state.activeThreads - 1);
  updateStats();
  upsertTaskRow(task);
}

// ── Export ────────────────────────────────────────────

$('exportBtn').addEventListener('click', () => {
  const json = JSON.stringify(
    state.tasks.map(t => ({
      id:         t.id,
      name:       t.name,
      status:     t.status,
      thread:     t.thread,
      durationMs: t.endTime && t.startTime ? t.endTime - t.startTime : null,
    })),
    null,
    2
  );

  const blob = new Blob([json], { type: 'application/json' });
  const url  = URL.createObjectURL(blob);
  const a    = document.createElement('a');
  a.href     = url;
  a.download = 'agentcore-tasks.json';
  a.click();
  URL.revokeObjectURL(url);
  addActivityEntry('Task history exported to JSON.', 'info');
});

// ── Clear Buttons ─────────────────────────────────────

$('clearChatBtn').addEventListener('click', () => {
  chatLog.innerHTML = '';
  addChatMsg('Console cleared.', 'info');
});

$('clearErrorBtn').addEventListener('click', () => {
  errorLog.innerHTML = '<div class="error-empty">No errors detected — all systems nominal.</div>';
});

// ── Suggestion Buttons ────────────────────────────────

document.querySelectorAll('.sug-btn').forEach(btn => {
  btn.addEventListener('click', () => handleInput(btn.dataset.cmd));
});

// ── Main Input Handler ────────────────────────────────

async function handleInput(raw) {
  const input = raw.trim();
  if (!input) return;

  addChatMsg(input, 'user');
  addActivityEntry(`Command received: "${input}"`, 'info');
  cmdInput.value = '';

  try {
    const type = parseCommand(input);

    switch (type) {
      case 'HISTORY':
        showHistory();
        break;
      case 'STATUS':
        showStatus();
        break;
      case 'HELP':
        showHelp();
        break;
      case 'EXIT':
        addChatMsg('AgentCore shutdown requested — refresh to restart.', 'agent');
        $('agentStatusLabel').textContent = 'OFFLINE';
        $('agentPulseDot').classList.add('offline');
        break;
      default:
        await dispatchTask(input, type);
    }
  } catch (err) {
    addChatMsg(`✕ ${err.message}`, 'error');
    addActivityEntry(err.message, 'error');
    addErrorEntry(err.message);
  }
}

// ── Keyboard Handler ──────────────────────────────────

cmdInput.addEventListener('keydown', e => {
  if (e.key === 'Enter') handleInput(cmdInput.value);
});

$('sendBtn').addEventListener('click', () => handleInput(cmdInput.value));

// ── Input Focus Styling ───────────────────────────────

cmdInput.addEventListener('focus', () => cmdInput.classList.add('focused'));
cmdInput.addEventListener('blur',  () => cmdInput.classList.remove('focused'));

// ── Boot Sequence ─────────────────────────────────────

(async () => {
  await sleep(300);
  addActivityEntry('AgentCore initialising…', 'info');
  await sleep(200);
  addActivityEntry('Thread pool created — capacity: 4', 'thread');
  await sleep(200);
  addActivityEntry('Concurrent task registry mounted', 'info');
  await sleep(200);
  addActivityEntry('Agent memory module online', 'info');
  await sleep(200);
  addActivityEntry('AgentCore ONLINE — all systems nominal', 'success');
  addChatMsg('All systems nominal. Ready for commands.', 'agent');
})();