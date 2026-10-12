import http from 'k6/http';
import ws from 'k6/ws';
import { check, fail } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { base, coordinator, smoke, accountAt, params, uuid, systemTags, summary } from './common.js';

const perRoom = Number(__ENV.PER_ROOM || (smoke ? 2 : 25));
const rooms = Number(__ENV.ROOMS || 1);
const total = perRoom * rooms;
const interval = Number(__ENV.INTERVAL_MS || 2000);
const messagesPerUser = Number(__ENV.MESSAGES || (smoke ? 2 : 150));
if (!(perRoom >= 1 && perRoom <= 25 && rooms >= 1 && rooms <= 10 && interval >= 1500 && messagesPerUser >= 1)) {
  throw new Error('Invalid chat sizing; 1..25 per room, 1..10 rooms, interval >=1500ms.');
}
const connected = new Rate('chat_connected');
const ready = new Rate('chat_ready');
const sent = new Counter('chat_sent');
const missing = new Counter('chat_missing');
const duplicates = new Counter('chat_duplicates');
const rejected = new Counter('chat_rejected');
const delivered = new Counter('chat_delivered');
const ackDuration = new Trend('chat_ack_ms', true);
const deliveryDuration = new Trend('chat_delivery_ms', true);
export const options = { systemTags,
  scenarios: { chat: { executor: 'per-vu-iterations', vus: total, iterations: 1,
    maxDuration: `${Math.ceil(messagesPerUser * interval / 1000) + 90}s` } },
  thresholds: { chat_connected: ['rate==1'], chat_ready: ['rate==1'], chat_sent: ['count>0'],
    chat_missing: ['count==0'], chat_duplicates: ['count==0'], chat_rejected: ['count==0'],
    chat_ack_ms: [`p(95)<${__ENV.P95_MS || 500}`], chat_delivery_ms: [`p(95)<${__ENV.P95_MS || 500}`],
    checks: ['rate==1'] },
};
export function setup() {
  // A single run identifier is shared across VUs; all subscriptions must be ready before any SEND.
  return { id: 'r' + Date.now().toString(36) + uuid().replace(/-/g, '').slice(0, 6) };
}
function frame(command, headers = {}, body = '') {
  return command + '\n' + Object.entries(headers).map(([key, value]) => key + ':' + value).join('\n')
    + '\n\n' + body + '\x00';
}
export default function (run) {
  const group = Math.floor((__VU - 1) / perRoom);
  const slot = (__VU - 1) % perRoom;
  const account = accountAt(group * 25 + slot);
  const resolved = http.post(`${base}/api/v1/locations/resolve`, JSON.stringify({
    latitude: account.latitude, longitude: account.longitude, accuracy_meters: 10,
  }), params(account, 'chat.location'));
  if (resolved.status !== 200) { ready.add(false); fail('Location fixture failed.'); }
  const regionId = resolved.json('data.region.sigungu.region_id');
  const found = http.get(`${base}/api/v1/regions/${regionId}/chat-room`, params(account, 'chat.room'));
  if (found.status !== 200) { ready.add(false); fail('Room lookup failed.'); }
  const roomId = found.json('data.room_id');
  const joined = http.post(`${base}/api/v1/chat-rooms/${roomId}/members`, JSON.stringify({
    location_resolution_token: resolved.json('data.location_resolution_token'),
  }), params(account, 'chat.join'));
  if (![200, 201].includes(joined.status)) { ready.add(false); fail('Room join failed.'); }
  const membershipId = joined.json('data.membership_id');
  let buffer = '';
  let isConnected = false;
  let armed = false;
  let startAt = 0;
  let sentCount = 0;
  const receipts = new Set();
  const pending = new Map();
  const received = new Set();
  const barrier = `${coordinator}/test/barriers/${run.id}`;
  const result = ws.connect(base.replace(/^http/, 'ws') + '/ws', {
    headers: { Origin: 'http://localhost:5173' }, tags: { name: 'chat.websocket' },
  }, (socket) => {
    socket.on('open', () => socket.send(frame('CONNECT', {
      'accept-version': '1.2', host: 'localhost', 'heart-beat': '10000,10000',
      Authorization: `Bearer ${account.access_token}`, room_id: roomId, membership_id: membershipId,
    })));
    socket.on('message', (message) => {
      buffer += String(message);
      let boundary;
      while ((boundary = buffer.indexOf('\x00')) >= 0) {
        const packet = buffer.slice(0, boundary).replace(/^\n+/, '');
        buffer = buffer.slice(boundary + 1);
        const divider = packet.indexOf('\n\n');
        if (divider < 0) continue;
        const lines = packet.slice(0, divider).split('\n');
        const command = lines.shift();
        const headers = {};
        for (const line of lines) {
          const index = line.indexOf(':');
          if (index >= 0) headers[line.slice(0, index)] = line.slice(index + 1);
        }
        if (command === 'CONNECTED') {
          isConnected = true;
          socket.send(frame('SUBSCRIBE', { id: 'events', destination: '/user/queue/chat-events', receipt: 'events' }));
          socket.send(frame('SUBSCRIBE', { id: 'room', destination: `/topic/chat-rooms/${roomId}`, receipt: 'room' }));
        } else if (command === 'RECEIPT') {
          receipts.add(headers['receipt-id']);
          if (!armed && receipts.has('events') && receipts.has('room')) {
            const response = http.post(barrier, JSON.stringify({ participant: __VU, expected: total }), {
              headers: { 'Content-Type': 'application/json' }, tags: { name: 'chat.barrier.ready' }, timeout: '3s',
            });
            armed = response.status === 200;
          }
        } else if (command === 'ERROR') {
          rejected.add(1);
          socket.close();
        } else if (command === 'MESSAGE') {
          let event;
          try { event = JSON.parse(packet.slice(divider + 2)); } catch (_) { rejected.add(1); continue; }
          const payload = event.data;
          if (event.type === 'CHAT_ACK') {
            const id = payload.message.client_message_id;
            if (pending.has(id)) {
              ackDuration.add(Date.now() - pending.get(id));
              pending.delete(id);
            } else duplicates.add(1);
          } else if (event.type === 'CHAT_REJECTED') {
            rejected.add(1);
          } else if (event.type === 'CHAT_MESSAGE' && String(payload.content).startsWith('load ' + run.id + ' ')) {
            const id = payload.client_message_id;
            if (received.has(id)) duplicates.add(1);
            else {
              received.add(id);
              delivered.add(1);
              const timestamp = String(payload.content).match(/ p(\d+) /);
              if (timestamp) deliveryDuration.add(Date.now() - Number(timestamp[1]));
            }
          }
        }
      }
      // Heartbeat-only frames have no NUL, keep at most an unfinished real STOMP frame.
      if (/^\n+$/.test(buffer)) buffer = '';
    });
    socket.setInterval(() => { if (isConnected) socket.send('\n'); }, 10000);
    socket.setInterval(() => {
      if (!armed || startAt) return;
      const state = http.get(barrier, { tags: { name: 'chat.barrier.state' }, timeout: '3s' });
      if (state.status === 200 && state.json('complete')) startAt = state.json('start_at');
    }, 500);
    socket.setInterval(() => {
      if (!startAt || Date.now() < startAt || sentCount >= messagesPerUser) return;
      const id = uuid();
      const now = Date.now();
      pending.set(id, now);
      socket.send(frame('SEND', { destination: `/app/chat-rooms/${roomId}/messages`, 'content-type': 'application/json' },
        JSON.stringify({ client_message_id: id, content: `load ${run.id} p${now} v${__VU} s${sentCount}` })));
      sentCount++;
      sent.add(1);
    }, interval);
    socket.setInterval(() => {
      if (startAt && Date.now() >= startAt + (messagesPerUser + 1) * interval + 5000) socket.close();
    }, 500);
    socket.setTimeout(() => socket.close(), messagesPerUser * interval + 65000);
  });
  connected.add(isConnected && result && result.status === 101);
  ready.add(armed && startAt > 0);
  const expected = perRoom * messagesPerUser;
  missing.add(Math.max(0, expected - received.size) + pending.size + Math.max(0, messagesPerUser - sentCount));
  duplicates.add(Math.max(0, received.size - expected));
  const left = http.del(`${base}/api/v1/chat-rooms/${roomId}/members/me?membership_id=${membershipId}`,
    null, params(account, 'chat.leave'));
  check(left, { 'membership left': (response) => response.status === 204 });
}
export const handleSummary = summary;
