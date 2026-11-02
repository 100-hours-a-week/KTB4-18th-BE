// Pure accounting/protocol helpers, shared by k6 and Node correctness tests.
export function frame(command, headers = {}, body = '') {
  const escape = (value) => String(value).replace(/\\/g, '\\\\').replace(/\r/g, '\\r').replace(/\n/g, '\\n').replace(/:/g, '\\c');
  return command + '\n' + Object.entries(headers).map(([k, v]) => escape(k) + ':' + escape(v)).join('\n') + '\n\n' + body + '\u0000';
}
export function consume(buffer) {
  const frames = [];
  let remaining = buffer.replace(/^\n+/, '');
  while (remaining.includes('\u0000')) {
    const end = remaining.indexOf('\u0000');
    const value = remaining.slice(0, end).replace(/^\n+/, '').replace(/\r\n/g, '\n');
    remaining = remaining.slice(end + 1).replace(/^\n+/, '');
    if (!value) continue;
    const boundary = value.indexOf('\n\n');
    if (boundary < 0) throw new Error('Malformed STOMP frame');
    const lines = value.slice(0, boundary).split('\n');
    const command = lines.shift();
    const headers = {};
    for (const line of lines) {
      const colon = line.indexOf(':');
      if (colon < 0) throw new Error('Malformed STOMP header');
      headers[line.slice(0, colon)] = line.slice(colon + 1).replace(/\\(c|n|r|\\)/g, (_, c) => ({ c: ':', n: '\n', r: '\r', '\\': '\\' }[c]));
    }
    frames.push({ command, headers, body: value.slice(boundary + 2) });
  }
  return { frames, remaining };
}
export function payload(id, length = 80) {
  // a-p alphabet: no digit sequences, URLs or variable length timestamps.
  const encoded = id.replace(/-/g, '').split('').map((c) => String.fromCharCode(97 + parseInt(c, 16))).join('');
  const text = 'load-' + encoded + '-';
  if (length < text.length || length > 300) throw new Error('Unsupported payload length');
  return text + 'a'.repeat(length - text.length);
}
// Same immutable broadcast body reaches many sockets. Cache parsing only;
// every socket still performs its own recipient, duplicate and latency checks.
export function eventDecoder(limit = 64) {
  const cache = new Map();
  return (body) => {
    if (cache.has(body)) return cache.get(body);
    const event = JSON.parse(body);
    if (cache.size >= limit) cache.delete(cache.keys().next().value);
    cache.set(body, event);
    return event;
  };
}
export function newRecord(id, sentAt, deadline, generations, context) {
  return { id, sentAt, deadline, expected: Uint16Array.from(generations), seen: new Uint8Array(generations.length), context,
    accepted: false, ack: false, rejected: false, received: 0, messageId: null };
}
export function accepted(record, messageId) {
  if (!Number.isSafeInteger(messageId) || messageId <= 0 || (record.messageId !== null && record.messageId !== messageId) || record.rejected) return false;
  record.accepted = true;
  record.messageId = messageId;
  return true;
}
export function delivery(record, index, generation, now) {
  if (now < record.sentAt) return { kind: 'clock_error' };
  if (record.expected[index] !== generation || generation === 0) return { kind: 'unexpected' };
  if (record.seen[index]) return { kind: 'duplicate' };
  if (now > record.deadline) {
    record.seen[index] = 2;
    return { kind: 'late' };
  }
  record.seen[index] = 1;
  record.received++;
  return { kind: 'unique', latency: now - record.sentAt };
}
export function totals(records) {
  const result = { attempted: records.length, accepted: 0, ack: 0, rejected: 0, unconfirmed: 0,
    ack_missing: 0, expected: 0, received: 0, missing: 0 };
  for (const record of records) {
    if (record.accepted) {
      result.accepted++;
      const count = record.expected.reduce((sum, generation) => sum + (generation > 0 ? 1 : 0), 0);
      result.expected += count;
      result.received += record.received;
      result.missing += count - record.received;
      if (!record.ack) result.ack_missing++;
    } else if (!record.rejected) result.unconfirmed++;
    if (record.ack) result.ack++;
    if (record.rejected) result.rejected++;
  }
  result.loss_rate = result.expected ? result.missing / result.expected : null;
  return result;
}
export function percentile(values, p) {
  if (!values.length) return null;
  const sorted = values.slice().sort((a, b) => a - b);
  return sorted[Math.max(0, Math.ceil(p * sorted.length) - 1)];
}
export function histogramPercentile(entries, p) {
  const ordered = entries.slice().sort((a, b) => a[0] - b[0]);
  const count = ordered.reduce((sum, pair) => sum + pair[1], 0);
  if (!count) return null;
  const rank = Math.ceil(p * count);
  let cumulative = 0;
  for (const [value, frequency] of ordered) {
    cumulative += frequency;
    if (cumulative >= rank) return value;
  }
}
