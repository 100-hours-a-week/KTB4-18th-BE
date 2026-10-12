import { SharedArray } from 'k6/data';
import { fail } from 'k6';

export const base = __ENV.BASE_URL || 'http://127.0.0.1:18080';
export const coordinator = __ENV.COORDINATOR_URL || 'http://127.0.0.1:18090';
for (const url of [base, coordinator]) {
  if (!/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(url)) {
    throw new Error('This suite only supports the dedicated localhost performance environment.');
  }
}
export const accounts = new SharedArray('performance-accounts', () =>
  JSON.parse(open(__ENV.ACCOUNTS_FILE || '../generated/accounts.json')));
export const runId = __ENV.RUN_ID || 'local-' + Date.now();
export const smoke = (__ENV.PHASE || 'smoke') === 'smoke';
export const systemTags = ['status', 'method', 'name', 'scenario', 'check', 'error_code'];
export function params(account, name) {
  return { headers: { Authorization: `Bearer ${account.access_token}`, 'Content-Type': 'application/json', Origin: 'http://localhost:5173' },
    tags: { name }, timeout: '60s' };
}
export function accountAt(index) {
  const account = accounts[index];
  if (!account) fail('Insufficient distinct fixture accounts; prepare the dataset again.');
  if (account.expires_at <= Date.now() / 1000 + 600) fail('Fixture tokens expired or close to expiry; regenerate.');
  return account;
}
export function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.floor(Math.random() * 16);
    return (c === 'x' ? r : (r & 3) | 8).toString(16);
  });
}
export function summary(data) {
  return { 'summary.json': JSON.stringify(data, null, 2) };
}
