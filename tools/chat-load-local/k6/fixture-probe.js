import http from 'k6/http';
import ws from 'k6/ws';
import { check } from 'k6';

// One account only. Proves authenticated REST + STOMP preparation, not load capacity.
const users = JSON.parse(open(__ENV.FIXTURE_FILE));
export const options = { vus: 1, iterations: 1, thresholds: { checks: ['rate==1'] } };
function frame(command, headers = {}) {
  return command + '\n' + Object.entries(headers).map(([k, v]) => k + ':' + v).join('\n') + '\n\n\u0000';
}
export default function () {
  const user = users[0];
  const base = 'http://127.0.0.1:18080';
  const auth = { headers: { Authorization: 'Bearer ' + user.access_token, Origin: base, 'Content-Type': 'application/json' } };
  const resolved = http.post(base + '/api/v1/locations/resolve', JSON.stringify(user.coordinate), auth);
  if (!check(resolved, { 'server issues fresh session-bound location token': (r) => r.status === 200 })) return;
  const joined = http.post(base + '/api/v1/chat-rooms/' + user.room_id + '/members', JSON.stringify({ location_resolution_token: resolved.json('data.location_resolution_token') }), auth);
  if (!check(joined, { 'authenticated regional room entry': (r) => r.status === 201 })) return;
  const membership = joined.json('data');
  let connected = false;
  let viewReady = false;
  const receipts = new Set();
  try {
    const response = ws.connect('ws://127.0.0.1:18080/ws', { headers: { Origin: base, 'Sec-WebSocket-Protocol': 'v12.stomp' } }, (socket) => {
      let buffer = '';
      socket.on('open', () => socket.send(frame('CONNECT', {
        'accept-version': '1.2', 'heart-beat': '0,0',
        Authorization: 'Bearer ' + user.access_token,
        room_id: user.room_id, membership_id: membership.membership_id,
        view_session_id: membership.view_session_id,
      })));
      socket.on('message', (text) => {
        buffer += text;
        while (buffer.includes('\u0000')) {
          const end = buffer.indexOf('\u0000');
          const message = buffer.slice(0, end).replace(/^\n+/, '');
          buffer = buffer.slice(end + 1);
          if (message.startsWith('CONNECTED\n')) {
            connected = true;
            socket.send(frame('SUBSCRIBE', { id: 'personal', destination: '/user/queue/chat-events', receipt: 'personal-ready' }));
            socket.send(frame('SUBSCRIBE', { id: 'topic', destination: '/topic/chat-rooms/' + user.room_id, receipt: 'topic-ready' }));
          } else if (message.startsWith('RECEIPT\n')) {
            const match = message.match(/(?:^|\n)receipt-id:([^\n]+)/);
            if (match) receipts.add(match[1]);
          } else if (message.startsWith('MESSAGE\n')) {
            const boundary = message.indexOf('\n\n');
            if (boundary !== -1) {
              const event = JSON.parse(message.slice(boundary + 2));
              if (event.type === 'CHAT_VIEW_READY') viewReady = event.data.view_session_id === membership.view_session_id;
            }
          }
          if (connected && viewReady && receipts.has('personal-ready') && receipts.has('topic-ready')) {
            socket.send(frame('DISCONNECT'));
            socket.close();
          }
        }
      });
      socket.setTimeout(() => socket.close(), 8000);
    });
    check(response, { 'WebSocket upgraded': (r) => r && r.status === 101 });
    check(connected, { 'actual STOMP CONNECT authenticated': (v) => v });
    check(viewReady, { 'current view is ready': (v) => v });
    check(receipts, { 'both subscriptions registered with receipts': (v) => v.has('personal-ready') && v.has('topic-ready') });
  } finally {
    const leave = http.del(base + '/api/v1/chat-rooms/' + user.room_id + '/members/me?membership_id=' + membership.membership_id + '&view_session_id=' + membership.view_session_id, null, auth);
    check(leave, { 'probe tab explicitly left': (r) => r.status === 204 });
  }
}
