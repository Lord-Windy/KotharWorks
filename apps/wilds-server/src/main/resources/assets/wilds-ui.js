/*
 * Ironwood Wilds structural console (Phase 0): vanilla JS — native EventSource,
 * requestAnimationFrame progress interpolation from (startedMs, dueMs, serverNowMs),
 * fetch for every command. Copyright 2026 Samuel "Lord-Windy" Brown. Apache 2.0.
 */
'use strict';
const body = document.body, LS_KEY = 'wilds:lastCursor';
const worldId = body.dataset.worldId || '0';
const csrf = (document.querySelector('meta[name="csrf-token"]') || {}).content || '';
const KINDS = ['task_started', 'task_batch_completed', 'task_failed', 'task_cancelled', 'place_discovered'];
// The offset is resynced against the server on every observation refresh; the progress loop then interpolates in logical time between frames.
let clockOffset = Number(body.dataset.serverNowMs || 0) - Date.now();
// Resume cursor: the SSR log tail is the floor; a previous visit's localStorage cursor raises it.
let cursor = Number((document.getElementById('event-log') || {}).dataset.lastCursor || 0);
try {
  const stored = JSON.parse(localStorage.getItem(LS_KEY) || 'null');
  if (stored && String(stored.worldId) === worldId && stored.cursor > cursor) cursor = stored.cursor;
} catch (ignored) { /* fresh start */ }
const logicalNow = () => Date.now() + clockOffset;
const $ = (id) => document.getElementById(id);


function notice(message) { const el = $('notice'); if (el) { el.textContent = message; el.hidden = false; } }
function setText(id, value) { const el = $(id); if (el) el.textContent = value; }
async function api(path, payload) {
  const res = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf }, body: JSON.stringify(payload) });
  if (res.status === 409) {
    notice('State changed elsewhere (STALE_STATE); refreshing the panel, nothing lost.');
    await refreshObservation();
  } else if (!res.ok) {
    res.json().catch(() => null).then((err) => notice('Request failed: '
      + (err && err.errorCode ? err.errorCode : res.status)));
  }
  return res;
}
const specText = (spec) => spec.type === 'wait' ? 'Wait ' + spec.durationMs + 'ms × ' + spec.batches : 'Travel → ' + spec.destination;
const duration = (ms) => Math.floor(ms / 1000) + '.' + Math.floor((ms % 1000) / 100) + 's';


function renderQueue(pending) {
  const ul = $('queue');
  if (!ul) return;
  ul.textContent = '';
  for (let i = 0; i < 5; i++) {
    const li = document.createElement('li');
    li.textContent = i < pending.length ? i + ': ' + specText(pending[i]) : i + ': (empty)';
    ul.appendChild(li);
  }
}
function renderTaskBar(actor) {
  const wrap = $('task-progress');
  if (!wrap) return;
  if (!actor.activeTask) { wrap.hidden = true; delete wrap.dataset.startedMs; return; }
  wrap.hidden = false;
  wrap.dataset.startedMs = String(actor.activeTask.startedAtMs);
  wrap.dataset.dueMs = String(actor.activeTask.dueAtMs);
  setText('task-name', specText(actor.activeTask.spec) + ' (batches left: ' + actor.activeTask.batchesLeft + ')');
}
function renderTravelAndPlace(actor, places) {
  const labels = {};
  for (const p of places) labels[p.id] = p.label;
  const place = places.find(p => p.id === actor.placeId);
  if (place) setText('place-label', place.label);
  setText('place-look', actor.look);
  const travel = $('travel');
  if (!travel) return;
  travel.textContent = '';
  for (const edge of (place ? place.edges : [])) {
    const btn = document.createElement('button');
    btn.className = 'cmd';
    btn.dataset.cmd = 'travel';
    btn.dataset.destination = edge.to;
    btn.dataset.expectedRevision = String(actor.revision);
    btn.textContent = 'Travel → ' + (labels[edge.to] || edge.to) + ' (' + duration(edge.travelDurationMs) + ')';
    travel.appendChild(btn);
  }
}
async function refreshObservation() {
  const res = await fetch('/api/v1/observation', { headers: { 'Accept': 'application/json' } });
  if (!res.ok) return;
  const obs = await res.json();
  const actor = obs.actors[0];
  if (!actor) return;
  clockOffset = Number(obs.nowMs || body.dataset.serverNowMs || 0) - Date.now();
  body.dataset.actorId = actor.id;
  body.dataset.expectedRevision = String(actor.revision);
  body.dataset.placeId = actor.placeId;
  document.querySelectorAll('[data-expected-revision]')
    .forEach((el) => { el.dataset.expectedRevision = String(actor.revision); });
  setText('revision', String(actor.revision));
  const tenths = Math.floor(actor.energy / 100);
  setText('energy', Math.floor(tenths / 10) + '.' + (tenths % 10) + '%');
  const policyBtn = $('fail-policy-btn');
  if (policyBtn) {
    policyBtn.dataset.policy = actor.failPolicy;
    policyBtn.textContent = 'Fail Policy: ' + actor.failPolicy;
  }
  renderQueue(actor.pendingQueue); renderTaskBar(actor); renderTravelAndPlace(actor, obs.places);
}
function rememberCursor(value) {
  cursor = value;
  try { localStorage.setItem(LS_KEY, JSON.stringify({ worldId: worldId, cursor: value })); }
  catch (ignored) { /* private mode */ }
}
function eventLi(kind, ev) {
  const li = document.createElement('li');
  li.textContent = '#' + ev.cursor + ' [' + kind + '] ' + (ev.privateSummary || ev.publicSummary || '');
  return li;
}
function appendEvent(kind, data) {
  const log = $('event-log');
  if (!log) return;
  log.appendChild(eventLi(kind, data));
  while (log.children.length > 30) log.removeChild(log.firstChild);
}
function connectStream() {
  const es = new EventSource('/api/v1/stream?since=' + cursor);
  for (const kind of KINDS) {
    es.addEventListener(kind, (ev) => {
      const data = JSON.parse(ev.data);
      if (data.cursor <= cursor) return;
      rememberCursor(data.cursor); appendEvent(kind, data); refreshObservation();
    });
  }
  es.onerror = () => { /* the browser reconnects automatically */ };
}
function commandEnvelope(button, revision) {
  const cmd = { cmdId: crypto.randomUUID(), targetActor: body.dataset.actorId,
    expectedRevision: revision };
  const task = (spec) => Object.assign(cmd, { type: 'enqueue', tasks: [spec] });
  if (button.dataset.cmd === 'travel') {
    return task({ type: 'travel', destination: button.dataset.destination });
  }
  if (button.dataset.cmd === 'wait5s') return task({ type: 'wait', durationMs: 5000, batches: 1 });
  if (button.dataset.cmd === 'cancel-pending' || button.dataset.cmd === 'cancel-active') {
    return Object.assign(cmd, { type: 'cancel_queue',
      cancelActive: button.dataset.cmd === 'cancel-active' });
  }
  return Object.assign(cmd, { type: 'set_fail_policy',
    policy: button.dataset.policy === 'ADVANCE' ? 'HALT_ON_FAIL' : 'ADVANCE' });
}
async function runCommand(button) {
  const revision = Number(button.dataset.expectedRevision || body.dataset.expectedRevision || 0);
  await api('/api/v1/commands', commandEnvelope(button, revision));
  await refreshObservation();
}
async function examine(placeId) {
  const drawer = $('drawer');
  if (!drawer) return;
  drawer.hidden = false;
  drawer.textContent = 'Examining…';
  const res = await fetch('/api/v1/examine?type=place&id=' + encodeURIComponent(placeId) + '&zoom=examine', { headers: { 'Accept': 'application/json' } });
  const json = await res.json().catch(() => null);
  drawer.textContent = res.ok && json ? json.text : 'Examine failed (' + res.status + ')';
}
function wireButtons() {
  document.addEventListener('click', (ev) => {
    const button = ev.target.closest('button');
    if (!button) return;
    if (button.dataset.cmd) runCommand(button);
    else if (button.dataset.examineId) examine(button.dataset.examineId);
    else if (button.id === 'drawer-close') { const d = $('drawer'); if (d) d.hidden = true; }
  });
  const claim = $('claim-seat-form');
  if (claim) {
    claim.addEventListener('submit', async (ev) => {
      ev.preventDefault();
      const res = await api('/api/v1/seats', { seatName: $('seat-name').value, controllerKind: 'HUMAN' });
      if (res.ok) window.location.reload();
    });
  }
}
// While You Were Away from the client-side cursor (?since= is rendered server-side).
function renderStoredCatchUp(stored) {
  if (!stored || String(stored.worldId) !== worldId
      || new URLSearchParams(location.search).has('since')) return;
  fetch('/api/v1/events?since=' + stored.cursor + '&limit=50').then((res) => res.json())
    .then((page) => {
      const panel = $('away-panel'), list = $('away-list');
      if (!panel || !list || !page.events.length) return;
      panel.hidden = false;
      for (const ev of page.events) list.appendChild(eventLi(ev.kind, ev));
    }).catch(() => { /* the live log still works */ });
}
function progressLoop() {
  const wrap = $('task-progress');
  if (wrap && !wrap.hidden && wrap.dataset.startedMs) {
    const started = Number(wrap.dataset.startedMs), due = Number(wrap.dataset.dueMs);
    const frac = due > started ? (logicalNow() - started) / (due - started) : 1;
    $('task-progress-fill').style.width = Math.min(100, Math.max(0, frac * 100)) + '%';
  }
  requestAnimationFrame(progressLoop);
}
wireButtons();
if (body.dataset.actorId) {
  try { renderStoredCatchUp(JSON.parse(localStorage.getItem(LS_KEY) || 'null')); }
  catch (ignored) { /* fresh start */ }
  refreshObservation(); connectStream(); requestAnimationFrame(progressLoop);
}
