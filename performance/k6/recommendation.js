import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import { base, smoke, accountAt, params, uuid, systemTags, summary } from './common.js';

const completed = new Rate('recommendation_completed');
const duration = new Trend('recommendation_e2e_ms', true);
const started = new Counter('recommendation_started');
const failed = new Counter('recommendation_failed');
const inputType = __ENV.INPUT_TYPE || 'TEXT';
if (!['TEXT', 'VOICE'].includes(inputType)) throw new Error('INPUT_TYPE must be TEXT or VOICE');
const audio = inputType === 'VOICE' ? open(__ENV.AUDIO_FILE || '../generated/audio-10s.mp4', 'b') : null;
export const options = {
  scenarios: smoke
    ? { smoke: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '90s' } }
    : { recommendations: { executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 1),
        timeUnit: '1s', duration: __ENV.DURATION || '5m',
        preAllocatedVUs: Number(__ENV.VUS || 50), maxVUs: Number(__ENV.MAX_VUS || 150), gracefulStop: '65s' } },
  systemTags,
  thresholds: { recommendation_completed: ['rate>=0.99'],
    recommendation_e2e_ms: [`p(95)<${__ENV.P95_MS || 5000}`],
    checks: ['rate==1'], ...(smoke ? {} : { dropped_iterations: ['count==0'] }) },
};
const conversationKey = uuid();
export default function () {
  const account = accountAt((__VU - 1) % 250);
  const start = Date.now();
  started.add(1);
  let prompt = '산책할 때 듣기 좋은 음악을 추천해줘';
  if (inputType === 'VOICE') {
    const transcriptionParams = params(account, 'recommendation.transcription');
    delete transcriptionParams.headers['Content-Type'];
    const transcription = http.post(`${base}/api/v1/speech-transcriptions`, {
      audio: http.file(audio, 'fixture.mp4', 'audio/mp4'),
    }, transcriptionParams);
    const transcribed = transcription.status === 200 && !!transcription.json('data.transcript');
    check(transcription, { 'voice transcription completed': () => transcribed });
    if (!transcribed) {
      completed.add(false);
      failed.add(1);
      return;
    }
    prompt = transcription.json('data.transcript');
  }
  const accepted = http.post(`${base}/api/v1/recommendations`, JSON.stringify({
    input_type: inputType, trigger_type: 'CHATBOT', conversation_key: conversationKey,
    prompt,
  }), params(account, 'recommendation.accept'));
  let completedAt = 0;
  let ok = accepted.status === 202;
  check(accepted, { 'recommendation accepted': () => ok });
  if (ok) {
    const id = accepted.json('data.recommendation_id');
    // This endpoint terminates after done/error. k6/http buffers the finite stream:
    // measure completion only; never describe HTTP TTFB as first text arrival.
    const events = http.get(`${base}/api/v1/recommendations/${id}/events`, {
      ...params(account, 'recommendation.events'),
      headers: { ...params(account, '').headers, Accept: 'text/event-stream' },
    });
    const frames = String(events.body || '').replace(/\r\n/g, '\n').split('\n\n');
    const names = frames.map((frame) => (frame.match(/^event:\s*(\S+)/m) || [])[1]).filter(Boolean);
    ok = events.status === 200 && names.filter((name) => name === 'done').length === 1
      && !names.includes('error') && names.includes('tracks');
    if (ok) {
      completedAt = Date.now();
      const result = http.get(`${base}/api/v1/recommendations/${id}`, params(account, 'recommendation.detail'));
      ok = result.status === 200 && result.json('data.status') === 'COMPLETED';
    }
    check(null, { 'SSE done and persisted COMPLETED': () => ok });
  }
  completed.add(ok);
  if (ok) duration.add(completedAt - start);
  else failed.add(1);
}
export const handleSummary = summary;
