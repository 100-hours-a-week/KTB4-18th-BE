import http from 'k6/http';
import crypto from 'k6/crypto';
import { WebSocket } from 'k6/websockets';
import { setTimeout, clearTimeout, setInterval, clearInterval } from 'k6/timers';
import { Counter, Gauge, Trend } from 'k6/metrics';
import { check } from 'k6';
import { frame, consume, payload, newRecord, accepted, delivery, totals, eventDecoder, histogramPercentile } from './chat-core.mjs';

const cfg = JSON.parse(open(__ENV.CHAT_CONFIG));
const users = JSON.parse(open(__ENV.FIXTURE_FILE));
const counters = {};
for (const name of ['ws_opened', 'ws_closed', 'normal_closes', 'stomp_connected', 'ready_events', 'reconnect_attempts', 'reconnect_ready', 'probe_closes',
  'unexpected_closes', 'socket_errors', 'prepare_errors', 'protocol_errors', 'clock_errors', 'scheduled_missed',
  'sent', 'ack', 'received', 'duplicate', 'unexpected_delivery', 'late', 'foreign_messages', 'rejected',
  'missing', 'unconfirmed', 'ack_missing', 'cleanup_errors']) counters[name] = new Counter('chat_' + name);
const wsGauge = new Gauge('chat_ws_open');
const readyGauge = new Gauge('chat_ready_connections');
const targetGauge = new Gauge('chat_target_connections');
const recvTrend = new Trend('chat_receive_ms', true);
const receiveP95 = new Gauge('chat_receive_exact_p95_ms');
const receiveP99 = new Gauge('chat_receive_exact_p99_ms');
const ackTrend = new Trend('chat_ack_ms', true);
const lagTrend = new Trend('chat_scheduler_lag_ms', true);
const loopTrend = new Trend('chat_event_loop_lag_ms', true);
const readyTrend = new Trend('chat_join_to_ready_ms', true);
const thresholds = { checks: ['rate==1'] };
for (const name of ['prepare_errors', 'protocol_errors', 'clock_errors', 'scheduled_missed', 'unexpected_closes',
  'socket_errors', 'foreign_messages', 'unexpected_delivery', 'cleanup_errors']) thresholds['chat_' + name] = ['count==0'];
for (const target of cfg.targets) {
  for (const name of ['missing', 'unconfirmed', 'ack_missing', 'rejected', 'duplicate']) {
    thresholds[`chat_${name}{target:${target},phase:measure}`] = ['count==0'];
  }
  thresholds[`chat_receive_ms{target:${target},phase:measure}`] = [];
  thresholds[`chat_ack_ms{target:${target},phase:measure}`] = [];
}
export const options = {
  scenarios: { room: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '30m', gracefulStop: '30s' } },
  thresholds,
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
};

let context = { target: '0', phase: 'prepare' };
const actors = [];
const records = new Map();
const decodeEvent = eventDecoder();
const buffered = new Map();
let sequence = 0;
let ending = false;
let lastClock = Date.now();
let activePhase = null;
let currentTarget = 0;
let failed = false;
const phaseReports = [];
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, Math.max(0, ms)));
const tags = () => ({ ...context });
function bump(name, count = 1, labels = context) {
  const key = name + '|' + labels.target + '|' + labels.phase + '|' + (labels.reason || '');
  if (!buffered.has(key)) buffered.set(key, { name, labels: { ...labels }, value: 0 });
  buffered.get(key).value += count;
}
function flush() {
  const points = [];
  for (const value of buffered.values()) counters[value.name].add(value.value, value.labels);
  if (cfg.raw_output === 'selected') {
    for (const value of buffered.values()) points.push({ metric: 'chat_' + value.name, value: value.value, tags: value.labels });
    rawBatch(points);
  }
  buffered.clear();
}
function rawBatch(points) {
  if (cfg.raw_output === 'selected' && points.length) {
    console.log('CHAT_RAW_BATCH ' + JSON.stringify({ time: new Date().toISOString(), points }));
  }
}
function clock() {
  const now = Date.now();
  if (now < lastClock) bump('clock_errors');
  lastClock = now;
  return now;
}
function readyCount() { return actors.filter((a) => a.ready).length; }
function gauges() {
  const count = readyCount();
  const open = actors.filter((a) => a.ws && a.ws.readyState === WebSocket.OPEN).length;
  wsGauge.add(open, context);
  readyGauge.add(count, context);
  targetGauge.add(currentTarget, context);
  rawBatch([{ metric: 'chat_ws_open', value: open, tags: tags() },
    { metric: 'chat_ready_connections', value: count, tags: tags() },
    { metric: 'chat_target_connections', value: currentTarget, tags: tags() }]);
  if (activePhase) activePhase.minimum_ready = Math.min(activePhase.minimum_ready, count);
  if (activePhase && activePhase.phase === 'measure' && cfg.latency_output === 'histogram') {
    const entries = Array.from(activePhase.histogram.entries());
    const p95 = histogramPercentile(entries, 0.95);
    if (p95 !== null) {
      receiveP95.add(p95, context);
      receiveP99.add(histogramPercentile(entries, 0.99), context);
    }
  }
}
function uuid() {
  const bytes = new Uint8Array(crypto.randomBytes(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = Array.from(bytes).map((b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
async function api(a, method, path, data = null) {
  const response = await http.asyncRequest(method, cfg.base_url + path, data === null ? null : JSON.stringify(data), {
    headers: { Authorization: 'Bearer ' + a.user.access_token, Origin: cfg.base_url, 'Content-Type': 'application/json' },
    timeout: '10s', tags: { ...context, name: path.split('?')[0].replace(/\/[0-9]+(?=\/|$)/g, '/:id') },
  });
  return response;
}
function transmit(a, command, headers = {}, body = '') {
  a.ws.send(frame(command, headers, body));
  a.lastSent = clock();
}
function recordEvent(a, generation, event) {
  if (event.type === 'CHAT_VIEW_READY') {
    a.flags.view = event.data.view_session_id === a.membership.view_session_id;
    return;
  }
  if (!['CHAT_MESSAGE', 'CHAT_ACK', 'CHAT_REJECTED'].includes(event.type)) return;
  const message = event.type === 'CHAT_ACK' ? event.data.message : event.data;
  const record = records.get(message.client_message_id);
  if (!record) { bump('foreign_messages'); return; }
  const labels = record.context;
  const now = clock();
  if (event.type === 'CHAT_REJECTED') {
    if (record.accepted || record.rejected) bump('protocol_errors', 1, labels);
    record.rejected = true;
    const known = ['EMPTY_CONTENT', 'CONTENT_TOO_LONG', 'INVALID_CONTENT', 'INVALID_REQUEST', 'INVALID_CLIENT_ID', 'RATE_LIMIT',
      'DUPLICATE_CONTENT', 'URL_NOT_ALLOWED', 'PERSONAL_INFORMATION', 'NOT_READY', 'DESTINATION_DENIED', 'CLIENT_ID_CONFLICT', 'RETRY_UNAVAILABLE', 'SEND_FAILED'];
    bump('rejected', 1, { ...labels, reason: known.includes(message.reason) ? message.reason : 'UNKNOWN' });
    return;
  }
  if (message.room_id !== a.user.room_id || !accepted(record, message.message_id)) { bump('protocol_errors', 1, labels); return; }
  if (event.type === 'CHAT_ACK') {
    if (a.index !== record.sender || event.data.membership_id !== a.membership.membership_id) { bump('protocol_errors', 1, labels); return; }
    if (record.ack) { bump('duplicate', 1, labels); return; }
    if (now < record.sentAt) { bump('clock_errors', 1, labels); return; }
    // ACK after the fixed receive window is evidence of acceptance, but cannot count as timely confirmation.
    if (now > record.deadline) { bump('late', 1, labels); return; }
    record.ack = true;
    bump('ack', 1, labels);
    if (labels.phase === 'measure') ackTrend.add(now - record.sentAt, labels);
    return;
  }
  const result = delivery(record, a.index, generation, now);
  if (result.kind === 'unique') {
    bump('received', 1, labels);
    if (labels.phase === 'measure') {
      if (cfg.latency_output === 'histogram') {
        const histogram = record.phase.histogram;
        histogram.set(result.latency, (histogram.get(result.latency) || 0) + 1);
      } else {
        recvTrend.add(result.latency, labels);
        record.phase.latencies.push(result.latency);
      }
    }
  } else bump({ duplicate: 'duplicate', unexpected: 'unexpected_delivery', late: 'late', clock_error: 'clock_errors' }[result.kind], 1, labels);
}
function connectSocket(a) {
  a.generation++;
  const generation = a.generation;
  const started = clock();
  a.flags = { stomp: false, view: false, receipts: new Set() };
  a.ready = false;
  let input = '';
  const ws = new WebSocket(cfg.base_url.replace(/^http/, 'ws') + '/ws', [], {
    headers: { Origin: cfg.base_url, 'Sec-WebSocket-Protocol': 'v12.stomp' }, tags: tags(),
  });
  a.ws = ws;
  const timeout = setTimeout(() => {
    if (a.ws === ws && !a.ready && ws.readyState < WebSocket.CLOSING) ws.close(1000, 'ready-timeout');
  }, cfg.ready_timeout_ms);
  ws.onopen = () => {
    bump('ws_opened');
    transmit(a, 'CONNECT', { 'accept-version': '1.2', 'heart-beat': '10000,10000',
      Authorization: 'Bearer ' + a.user.access_token, room_id: a.user.room_id,
      membership_id: a.membership.membership_id, view_session_id: a.membership.view_session_id });
  };
  ws.onmessage = (event) => {
    if (a.ws !== ws || a.generation !== generation) return;
    try {
      input += event.data;
      if (input.length > 65536) throw new Error('frame-buffer-limit');
      const parsed = consume(input); input = parsed.remaining;
      for (const message of parsed.frames) {
        if (message.command === 'CONNECTED') {
          a.flags.stomp = true; bump('stomp_connected');
          transmit(a, 'SUBSCRIBE', { id: 'personal', destination: '/user/queue/chat-events', receipt: 'personal-ready' });
          transmit(a, 'SUBSCRIBE', { id: 'room', destination: '/topic/chat-rooms/' + a.user.room_id, receipt: 'room-ready' });
        } else if (message.command === 'RECEIPT') a.flags.receipts.add(message.headers['receipt-id']);
        else if (message.command === 'MESSAGE') recordEvent(a, generation, decodeEvent(message.body));
        else if (message.command === 'ERROR') throw new Error('stomp-error');
        if (!a.ready && a.flags.stomp && a.flags.view && a.flags.receipts.has('personal-ready') && a.flags.receipts.has('room-ready')) {
          a.ready = true; clearTimeout(timeout); bump('ready_events');
          if (generation > 1) bump('reconnect_ready');
          readyTrend.add(clock() - started, context); gauges();
        }
      }
    } catch (_) {
      bump('protocol_errors');
      if (ws.readyState === WebSocket.OPEN) ws.close(1000, 'protocol-error');
    }
  };
  ws.onerror = () => { if (!ending) bump('socket_errors'); };
  ws.onclose = () => {
    clearTimeout(timeout);
    if (a.ws !== ws) return;
    bump('ws_closed');
    a.ready = false; gauges();
    if (ending || a.ending) { bump('normal_closes'); return; }
    if (a.probeClose) { bump('probe_closes'); a.probeClose = false; }
    else bump('unexpected_closes');
    if (a.reconnects < cfg.reconnect_max) {
      a.reconnects++; bump('reconnect_attempts');
      setTimeout(() => { if (!ending && !a.ending) connectSocket(a); }, cfg.reconnect_delay_ms);
    }
  };
}
async function prepare(index) {
  const a = { index, user: users[index], membership: null, generation: 0, ready: false, ws: null,
    lastSent: 0, lastMessage: 0, reconnects: 0, ending: false, probeClose: false };
  actors.push(a);
  const resolved = await api(a, 'POST', '/api/v1/locations/resolve', a.user.coordinate);
  if (resolved.status !== 200) throw new Error('location-prepare-failed');
  const joined = await api(a, 'POST', '/api/v1/chat-rooms/' + a.user.room_id + '/members', {
    location_resolution_token: resolved.json('data.location_resolution_token'),
  });
  if (![200, 201].includes(joined.status)) throw new Error('join-prepare-failed');
  a.membership = joined.json('data');
  connectSocket(a);
}
async function expand(target) {
  context = { target: String(target), phase: 'prepare' };
  currentTarget = target;
  const pending = [];
  const start = actors.length;
  // Start new actors at a fixed rate; all joins use async HTTP so existing WS callbacks keep running.
  for (let index = start; index < target; index++) {
    pending.push(prepare(index).catch(() => { bump('prepare_errors'); failed = true; }));
    await delay(cfg.join_interval_ms);
  }
  await Promise.all(pending);
  const deadline = clock() + cfg.ready_timeout_ms;
  while (readyCount() < target && clock() < deadline && !failed) await delay(50);
  gauges();
  if (failed || readyCount() !== target) { bump('prepare_errors'); throw new Error('target-not-ready'); }
}
async function window(target, phase, seconds, inject = false) {
  context = { target: String(target), phase };
  for (const name of Object.keys(counters)) counters[name].add(0, context);
  const start = clock();
  const end = start + seconds * 1000;
  const data = { target, phase, start_ms: start, end_ms: end, requested: seconds * cfg.rate,
    missed: 0, minimum_ready: readyCount(), latencies: [], histogram: new Map(), records: [] };
  activePhase = data;
  const interval = 1000 / cfg.rate;
  const pool = actors.slice(0, cfg.sender_count);
  let injected = false;
  for (let slot = 0; slot < data.requested; slot++) {
    const due = start + slot * interval;
    await delay(due - clock());
    const now = clock();
    const lag = now - due;
    lagTrend.add(Math.max(0, lag), context);
    if (inject && !injected && now >= start + 500) {
      const actor = actors[target - 1]; actor.probeClose = true;
      actor.ws.close(1000, 'smoke-reconnect'); injected = true;
    }
    const sender = pool[sequence++ % pool.length];
    if (lag >= interval || now >= end || !sender.ready || now - sender.lastMessage < 1100) {
      bump('scheduled_missed'); data.missed++; continue; // Never burst to catch up missed slots.
    }
    const id = uuid();
    const generations = actors.map((a) => a.ready ? a.generation : 0);
    const record = newRecord(id, now, end + cfg.drain_ms, generations, tags());
    record.sender = sender.index; record.phase = data;
    records.set(id, record); data.records.push(record);
    sender.lastMessage = now;
    transmit(sender, 'SEND', { destination: '/app/chat-rooms/' + sender.user.room_id + '/messages', 'content-type': 'application/json' },
      JSON.stringify({ client_message_id: id, content: payload(id, cfg.message_length) }));
    bump('sent');
  }
  await delay(end - clock());
  activePhase = null;
  return data;
}
function report(data) {
  const counts = totals(data.records);
  if (data.phase === 'measure') {
    for (const name of ['missing', 'unconfirmed', 'ack_missing']) counters[name].add(counts[name], { target: String(data.target), phase: data.phase });
    rawBatch(['missing', 'unconfirmed', 'ack_missing'].map((name) => ({ metric: 'chat_' + name,
      value: counts[name], tags: { target: String(data.target), phase: data.phase } })));
  }
  const sorted = data.latencies.sort((a, b) => a - b);
  const entries = Array.from(data.histogram.entries()).sort((a, b) => a[0] - b[0]);
  const rank = (p) => cfg.latency_output === 'histogram' ? histogramPercentile(entries, p)
    : (sorted.length ? sorted[Math.ceil(p * sorted.length) - 1] : null);
  if (data.phase === 'measure' && cfg.latency_output === 'histogram') {
    const labels = { target: String(data.target), phase: data.phase };
    if (rank(0.95) !== null) { receiveP95.add(rank(0.95), labels); receiveP99.add(rank(0.99), labels); }
    console.log('CHAT_LATENCY_HISTOGRAM ' + JSON.stringify({ target: data.target, phase: data.phase, unit: 'ms', entries }));
  }
  const row = { target: data.target, phase: data.phase, seconds: (data.end_ms - data.start_ms) / 1000,
    target_messages: data.requested, scheduled_missed: data.missed, minimum_ready: data.minimum_ready,
    ...counts, receive_p95_ms: rank(0.95), receive_p99_ms: rank(0.99) };
  phaseReports.push(row);
  console.log('CHAT_PHASE ' + JSON.stringify(row));
}
async function cleanup() {
  ending = true; activePhase = null; context = { target: String(currentTarget), phase: 'cleanup' };
  const batchSize = cfg.cleanup_batch_size || 5;
  for (let index = 0; index < actors.length; index += batchSize) {
    await Promise.all(actors.slice(index, index + batchSize).map(async (a) => {
      a.ending = true;
      try {
        if (a.membership) {
          const response = await api(a, 'DELETE', '/api/v1/chat-rooms/' + a.user.room_id + '/members/me?membership_id=' + a.membership.membership_id + '&view_session_id=' + a.membership.view_session_id);
          if (response.status !== 204) bump('cleanup_errors');
        }
      } catch (_) { bump('cleanup_errors'); }
      finally {
        if (a.ws && a.ws.readyState < WebSocket.CLOSING) a.ws.close(1000, 'test-cleanup');
      }
    }));
    await delay(cfg.cleanup_interval_ms || 100);
  }
  const closeDeadline = clock() + 3000;
  while (actors.some((a) => a.ws && a.ws.readyState !== WebSocket.CLOSED) && clock() < closeDeadline) await delay(20);
  if (actors.some((a) => a.ws && a.ws.readyState !== WebSocket.CLOSED)) bump('cleanup_errors');
  gauges(); flush();
}
export default async function () {
  for (const metric of Object.values(counters)) metric.add(0, context);
  let lastTick = clock();
  const pump = setInterval(() => {
    const now = clock(); loopTrend.add(Math.max(0, now - lastTick - 1000), context); lastTick = now;
    for (const a of actors) if (a.ws && a.ws.readyState === WebSocket.OPEN && now - a.lastSent >= 10000) { a.ws.send('\n'); a.lastSent = now; }
    gauges(); flush();
  }, 1000);
  try {
    for (let index = 0; index < cfg.targets.length; index++) {
      const target = cfg.targets[index];
      await expand(target);
      if (cfg.settle_ms > 0) {
        context = { target: String(target), phase: 'settle' };
        console.log('CHAT_SETTLE ' + JSON.stringify({ target, seconds: cfg.settle_ms / 1000 }));
        await delay(cfg.settle_ms); // Drain join/presence traffic before the fixed sending windows.
      }
      const warmup = await window(target, 'warmup', cfg.warmup_seconds, cfg.reconnect_probe && index === 1);
      const measured = await window(target, 'measure', cfg.measure_seconds);
      context = { target: String(target), phase: 'drain' };
      await delay(cfg.drain_ms); flush();
      report(warmup); report(measured);
      if (index === 0 && cfg.targets.length > 1 && cfg.pause_ms > 0) {
        context = { target: String(target), phase: 'pause' };
        console.log('CHAT_PAUSE ' + JSON.stringify({ seconds: cfg.pause_ms / 1000, mode: cfg.mode }));
        await delay(cfg.pause_ms); // Same fixed no-send pause before/after; manual B startup belongs here.
      }
    }
  } catch (_) {
    failed = true;
    check(false, { 'round prepared and executed without a fatal error': (v) => v });
    console.log('CHAT_ABORT preparation-or-runtime-error; inspect counters and server health (no credentials logged)');
  } finally {
    await cleanup(); clearInterval(pump); flush();
    for (const row of phaseReports.filter((r) => r.phase === 'measure')) {
      check(row, { 'target connections and fixed send rate met': (r) => r.minimum_ready === r.target && r.attempted === r.target_messages && r.scheduled_missed === 0 });
      check(row, { 'all sends accepted, ACKed and delivered once in receive window': (r) => r.accepted === r.target_messages && r.ack === r.target_messages && r.unconfirmed === 0 && r.missing === 0 && r.received === r.expected });
    }
    check(failed, { 'all configured stages completed': (v) => !v && phaseReports.filter((r) => r.phase === 'measure').length === cfg.targets.length });
  }
}
export function handleSummary(data) {
  return { [__ENV.CHAT_SUMMARY]: JSON.stringify(data, null, 2) };
}
