import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep } from 'k6';

// Environment wiring only: this is not an authenticated chat/load test.
export const options = {
  vus: 1,
  duration: '10s',
  thresholds: { checks: ['rate==1'], http_req_failed: ['rate==0'] },
};
export function setup() {
  // Transport upgrade only; authenticated STOMP/chat verification is a later step.
  const response = ws.connect('ws://127.0.0.1:18080/ws', {
    headers: { Origin: 'http://127.0.0.1:18080' },
  }, (socket) => {
    socket.on('open', () => socket.close());
    socket.setTimeout(() => socket.close(), 3000);
  });
  check(response, { 'WebSocket transport upgrade through proxy': (r) => r && r.status === 101 });
}
export default function () {
  const res = http.get('http://127.0.0.1:18080/api/v1/terms');
  check(res, { 'public terms API is reachable through proxy': (r) => r.status === 200 });
  sleep(1);
}
