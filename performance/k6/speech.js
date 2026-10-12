import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { base, smoke, accountAt, params, systemTags, summary } from './common.js';
const audio = open(__ENV.AUDIO_FILE || '../generated/audio-10s.mp4', 'b');
const success = new Rate('speech_completed');
const duration = new Trend('speech_e2e_ms', true);
export const options = { systemTags,
  scenarios: smoke
    ? { smoke: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '60s' } }
    : { speech: { executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 1), timeUnit: '1s',
        duration: __ENV.DURATION || '5m', preAllocatedVUs: 20, maxVUs: 100, gracefulStop: '65s' } },
  thresholds: { speech_completed: ['rate>=0.99'], speech_e2e_ms: [`p(95)<${__ENV.P95_MS || 5000}`],
    checks: ['rate==1'], ...(smoke ? {} : { dropped_iterations: ['count==0'] }) },
};
export default function () {
  const account = accountAt((__VU - 1) % 250);
  const start = Date.now();
  const requestParams = params(account, 'speech.transcription');
  delete requestParams.headers['Content-Type'];
  const response = http.post(`${base}/api/v1/speech-transcriptions`, {
    audio: http.file(audio, 'fixture.mp4', 'audio/mp4'),
  }, requestParams);
  const ok = response.status === 200 && !!response.json('data.transcript');
  check(response, { 'transcription completed': () => ok });
  success.add(ok);
  if (ok) duration.add(Date.now() - start);
}
export const handleSummary = summary;
