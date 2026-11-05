import test from 'node:test';
import assert from 'node:assert/strict';
import { frame, consume, payload, newRecord, accepted, delivery, totals, percentile, eventDecoder, histogramPercentile } from './chat-core.mjs';
const record = () => newRecord('id', 100, 200, [1, 1, 0], {});
test('integer latency histogram preserves exact nearest-rank percentiles of all samples', () => {
  const samples = Array.from({ length: 2500 }, (_, i) => (i * 7919) % 1001);
  const counts = new Map();
  for (const sample of samples) counts.set(sample, (counts.get(sample) || 0) + 1);
  for (const p of [0.5, 0.95, 0.99, 1]) {
    assert.equal(histogramPercentile(Array.from(counts.entries()), p), percentile(samples, p));
  }
  assert.equal(histogramPercentile([], 0.95), null);
  assert.equal(histogramPercentile([[20, 5], [1, 95]], 0.95), 1);
  assert.equal(histogramPercentile([[20, 5], [1, 95]], 0.99), 20);
});
test('bounded parse cache reuses identical broadcasts but keeps delivery accounting per recipient', () => {
  const decode = eventDecoder(2);
  const body = '{"type":"CHAT_MESSAGE","data":{"message_id":12}}';
  const first = decode(body);
  assert.equal(decode(body), first);
  const r = record(); accepted(r, first.data.message_id);
  assert.equal(delivery(r, 0, 1, 110).kind, 'unique');
  assert.equal(delivery(r, 1, 1, 120).kind, 'unique');
  assert.equal(delivery(r, 0, 1, 130).kind, 'duplicate');
  decode('{"type":"CHAT_ACK"}'); decode('{"type":"CHAT_VIEW_READY"}');
  assert.notEqual(decode(body), first);
  assert.throws(() => decode('invalid JSON'));
});
test('fragmented and coalesced STOMP frames with heartbeats and escaped headers', () => {
  const value = frame('MESSAGE', { receipt: 'a:b\\c' }, JSON.stringify({ text: '한글' }));
  const first = consume('\n' + value.slice(0, 12));
  assert.equal(first.frames.length, 0);
  const rest = consume(first.remaining + value.slice(12) + '\n' + frame('RECEIPT', { 'receipt-id': 'ready' }));
  assert.equal(rest.frames.length, 2);
  assert.equal(rest.frames[0].headers.receipt, 'a:b\\c');
  assert.equal(JSON.parse(rest.frames[0].body).text, '한글');
  assert.equal(rest.remaining, '');
});
test('ACK/broadcast order does not change delivery or duplicate accounting', () => {
  const r = record();
  assert.ok(accepted(r, 12));
  assert.equal(delivery(r, 0, 1, 110).kind, 'unique');
  r.ack = true;
  assert.equal(delivery(r, 0, 1, 115).kind, 'duplicate');
  delivery(r, 1, 1, 120);
  assert.deepEqual(totals([r]), { attempted: 1, accepted: 1, ack: 1, rejected: 0, unconfirmed: 0, ack_missing: 0, expected: 2, received: 2, missing: 0, loss_rate: 0 });
});
test('no ACK and no broadcast remains unconfirmed, never zero-loss success', () => {
  const t = totals([record()]);
  assert.equal(t.unconfirmed, 1);
  assert.equal(t.expected, 0);
  assert.equal(t.loss_rate, null);
});
test('broadcast without ACK still counts missing ACK and a known committed message', () => {
  const r = record(); accepted(r, 10); delivery(r, 0, 1, 130);
  assert.equal(totals([r]).ack_missing, 1);
  assert.equal(totals([r]).missing, 1);
});
test('policy rejection is separated from missing accepted deliveries', () => {
  const r = record(); r.rejected = true;
  assert.equal(totals([r]).rejected, 1);
  assert.equal(totals([r]).missing, 0);
  assert.equal(totals([r]).unconfirmed, 0);
});
test('new connection generation and non-recipients cannot fill old delivery gaps', () => {
  const r = record(); accepted(r, 10);
  assert.equal(delivery(r, 0, 2, 120).kind, 'unexpected');
  assert.equal(delivery(r, 2, 1, 120).kind, 'unexpected');
  assert.equal(totals([r]).missing, 2);
});
test('deadline is inclusive; late arrival cannot erase missing delivery', () => {
  const r = record(); accepted(r, 10);
  assert.equal(delivery(r, 0, 1, 200).kind, 'unique');
  assert.equal(delivery(r, 1, 1, 201).kind, 'late');
  assert.equal(totals([r]).missing, 1);
});
test('clock reversal and conflicting IDs are explicit failures', () => {
  const r = record(); accepted(r, 10);
  assert.equal(delivery(r, 0, 1, 99).kind, 'clock_error');
  assert.equal(accepted(r, 11), false);
  assert.equal(r.received, 0);
});
test('unique payloads have fixed length and no digit sequences', () => {
  const a = payload('00112233-4455-4677-8899-aabbccddeeff');
  const b = payload('00112233-4455-4677-8899-aabbccddeefe');
  assert.equal(a.length, 80); assert.equal(b.length, 80); assert.notEqual(a, b);
  assert.equal(/\d/.test(a), false);
});
test('reported nearest-rank p95 is independent of receive ordering', () => {
  assert.equal(percentile([100, 1, 4, 2, 3], 0.95), 100);
  assert.equal(percentile([], 0.95), null);
});
