/*
 * Ironwood Wilds structural console (Phase 0): vanilla JS — native EventSource,
 * requestAnimationFrame progress interpolation from (startedMs, dueMs, serverNowMs),
 * fetch for every command. Copyright 2026 Samuel "Lord-Windy" Brown. Apache 2.0.
 */
'use strict';
const $ = (id) => document.getElementById(id), body = document.body;
const worldId = body.dataset.worldId || '0', seatId = body.dataset.seatId || '';
const timeScale = Math.max(1, Number(body.dataset.timeScale || 1));
const lsKey = 'wilds:lastCursor:' + worldId + ':' + seatId;
const metaCsrf = document.querySelector('meta[name="csrf-token"]'), csrf = metaCsrf ? metaCsrf.content : '';
const KINDS = ['owner_registered', 'task_started', 'task_batch_completed', 'task_failed', 'task_cancelled', 'place_discovered'];
let anchorLogical = Number(body.dataset.serverNowMs || 0), anchorWall = Date.now(), storedCursor = 0;
const logEl = $('event-log'), ssrCursor = Number((logEl && logEl.dataset.lastCursor) || 0);
if (seatId) {
  try {
    const stored = JSON.parse(localStorage.getItem(lsKey) || 'null');
    if (stored && Number.isFinite(stored.cursor) && stored.cursor > 0) storedCursor = stored.cursor;
  } catch (ignored) { /* fresh start */ }
}
if (storedCursor > ssrCursor) storedCursor = 0;
let cursor = Math.max(ssrCursor, storedCursor), refreshing = false, refreshQueued = false;
const logicalNow = () => anchorLogical + (Date.now() - anchorWall) * timeScale;
const withSeat = (url) => !seatId ? url : url + (url.includes('?') ? '&' : '?') + 'seatId=' + encodeURIComponent(seatId);

function notice(message) { const el = $('notice'); if (el) { el.textContent = message; el.hidden = false; } }
function setText(id, value) { const el = $(id); if (el) el.textContent = value; }
function rememberCursor(value) {
  cursor = Math.max(cursor, value);
  if (!seatId) return;
  try { localStorage.setItem(lsKey, JSON.stringify({ cursor: cursor })); }
  catch (ignored) { /* private mode */ }
}
async function api(path, payload) {
  const res = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf }, body: JSON.stringify(payload) });
  if (!res.ok) {
    const err = await res.json().catch(() => null);
    const code = err && err.errorCode ? err.errorCode : String(res.status);
    if (res.status === 409 && code === 'STALE_STATE') {
      notice('State changed elsewhere (STALE_STATE); refreshing the panel, nothing lost.');
      await refreshObservation();
    } else {
      notice('Request failed: ' + code + (err && err.message ? ' — ' + err.message : ''));
      if (res.status === 409 && body.dataset.actorId) await refreshObservation();
    }
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
function renderTaskBar(actor, nowMs) {
  const wrap = $('task-progress');
  if (!wrap) return;
  if (!actor.activeTask) { wrap.hidden = true; delete wrap.dataset.startedMs; return; }
  wrap.hidden = false;
  Object.assign(wrap.dataset, { startedMs: String(actor.activeTask.startedAtMs), dueMs: String(actor.activeTask.dueAtMs), serverNowMs: String(nowMs) });
  setText('task-name', specText(actor.activeTask.spec) + ' (batches left: ' + actor.activeTask.batchesLeft + ')');
}
function renderTravelAndPlace(actor, places) {
  const labels = {};
  for (const p of places) labels[p.id] = p.label;
  const place = places.find((p) => p.id === actor.placeId);
  if (place) setText('place-label', place.label);
  setText('place-look', actor.look);
  const examineBtn = $('examine-btn');
  if (examineBtn) examineBtn.dataset.examineId = actor.placeId;
  const travel = $('travel');
  if (!travel) return;
  travel.textContent = '';
  for (const edge of (place ? place.edges : [])) {
    const btn = Object.assign(document.createElement('button'), { type: 'button', className: 'cmd' });
    btn.dataset.cmd = 'travel';
    btn.dataset.destination = edge.to;
    btn.dataset.expectedRevision = String(actor.revision);
    btn.textContent = 'Travel → ' + (labels[edge.to] || edge.to) + ' (' + duration(edge.travelDurationMs) + ')';
    travel.appendChild(btn);
  }
}
async function pullObservation() {
  const res = await fetch(withSeat('/api/v1/observation'), { headers: { 'Accept': 'application/json' } });
  if (!res.ok) return;
  const obs = await res.json(), actor = obs.actors[0];
  if (!actor) return;
  anchorLogical = Number(obs.nowMs ?? body.dataset.serverNowMs ?? 0); anchorWall = Date.now();
  Object.assign(body.dataset, { actorId: actor.id, expectedRevision: String(actor.revision), placeId: actor.placeId, serverNowMs: String(anchorLogical) });
  document.querySelectorAll('[data-expected-revision]').forEach((el) => { el.dataset.expectedRevision = String(actor.revision); });
  setText('now-ms', String(anchorLogical));
  setText('revision', String(actor.revision));
  const tenths = Math.floor(actor.energy / 100);
  setText('energy', Math.floor(tenths / 10) + '.' + (tenths % 10) + '%');
  setText('fail-policy', actor.failPolicy);
  const policyBtn = $('fail-policy-btn');
  if (policyBtn) { policyBtn.dataset.policy = actor.failPolicy; policyBtn.textContent = 'Fail Policy: ' + actor.failPolicy; }
  renderQueue(actor.pendingQueue); renderTaskBar(actor, anchorLogical); renderTravelAndPlace(actor, obs.places);
}
async function refreshObservation() {
  if (refreshing) { refreshQueued = true; return; }
  refreshing = true;
  try { do { refreshQueued = false; await pullObservation(); } while (refreshQueued); }
  finally { refreshing = false; }
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
  const es = new EventSource(withSeat('/api/v1/stream?since=' + cursor));
  for (const kind of KINDS) {
    es.addEventListener(kind, (ev) => {
      const data = JSON.parse(ev.data);
      if (data.cursor <= cursor) return;
      rememberCursor(data.cursor); appendEvent(kind, data); refreshObservation();
    });
  }
  es.onerror = () => { /* EventSource reconnects automatically with Last-Event-ID */ };
}
function commandEnvelope(button, revision) {
  const cmd = { cmdId: crypto.randomUUID(), targetActor: body.dataset.actorId, expectedRevision: revision };
  const task = (spec) => Object.assign(cmd, { type: 'enqueue', tasks: [spec] });
  if (button.dataset.cmd === 'travel') return task({ type: 'travel', destination: button.dataset.destination });
  if (button.dataset.cmd === 'wait5s') return task({ type: 'wait', durationMs: 5000, batches: 1 });
  if (button.dataset.cmd === 'cancel-pending' || button.dataset.cmd === 'cancel-active') {
    return Object.assign(cmd, { type: 'cancel_queue', cancelActive: button.dataset.cmd === 'cancel-active' });
  }
  return Object.assign(cmd, { type: 'set_fail_policy', policy: button.dataset.policy === 'ADVANCE' ? 'HALT_ON_FAIL' : 'ADVANCE' });
}
async function runCommand(button) {
  const revision = Number(button.dataset.expectedRevision ?? body.dataset.expectedRevision ?? 0);
  const res = await api(withSeat('/api/v1/commands'), commandEnvelope(button, revision));
  if (res.ok) { const n = $('notice'); if (n) n.hidden = true; await refreshObservation(); }
}
async function examine(placeId) {
  const drawer = $('drawer');
  if (!drawer) return;
  drawer.hidden = false;
  setText('drawer-text', 'Examining…');
  const res = await fetch(withSeat('/api/v1/examine?type=place&id=' + encodeURIComponent(placeId) + '&zoom=examine'), { headers: { 'Accept': 'application/json' } });
  const json = await res.json().catch(() => null);
  setText('drawer-text', res.ok && json ? json.text : 'Examine failed (' + res.status + ')');
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
      const res = await api('/api/v1/seats', { seatName: $('seat-name').value, controllerKind: ($('controller-kind') || { value: 'HUMAN' }).value });
      if (res.ok) window.location.reload();
    });
  }
}
function renderStoredCatchUp(prevCursor) {
  if (!prevCursor || new URLSearchParams(location.search).has('since')) return;
  fetch(withSeat('/api/v1/events?since=' + prevCursor + '&limit=50')).then((res) => res.json()).then((page) => {
    const panel = $('away-panel'), list = $('away-list');
    if (!panel || !list || !page.events || !page.events.length) return;
    panel.hidden = false;
    for (const ev of page.events) list.appendChild(eventLi(ev.kind, ev));
    if (page.nextSince > cursor) rememberCursor(page.nextSince);
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
  rememberCursor(cursor); renderStoredCatchUp(storedCursor);
  refreshObservation(); connectStream(); requestAnimationFrame(progressLoop);
}
