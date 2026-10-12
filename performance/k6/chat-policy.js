import http from 'k6/http';
import { check, fail } from 'k6';
import { base, accountAt, params, systemTags, summary } from './common.js';
export const options = { vus: 1, iterations: 1, systemTags, thresholds: { checks: ['rate==1'] } };
export default function () {
  const memberships = [];
  let roomId;
  try {
    const first = accountAt(0);
    for (let index = 0; index < 26; index++) {
      const account = accountAt(index);
      const location = http.post(`${base}/api/v1/locations/resolve`, JSON.stringify({
        latitude: first.latitude, longitude: first.longitude, accuracy_meters: 10,
      }), params(account, 'policy.location'));
      if (location.status !== 200) fail('Location setup failed.');
      if (!roomId) {
        const lookup = http.get(`${base}/api/v1/regions/${location.json('data.region.sigungu.region_id')}/chat-room`,
          params(account, 'policy.room'));
        roomId = lookup.json('data.room_id');
      }
      const joined = http.post(`${base}/api/v1/chat-rooms/${roomId}/members`, JSON.stringify({
        location_resolution_token: location.json('data.location_resolution_token'),
      }), params(account, 'policy.join'));
      if (index < 25) {
        check(joined, { 'first 25 seats reserved': (response) => response.status === 201 });
        if (joined.status === 201) memberships.push({ account, id: joined.json('data.membership_id') });
      } else {
        check(joined, { '26th distinct account rejected with capacity conflict': (response) => response.status === 409 });
        if ([200, 201].includes(joined.status)) memberships.push({ account, id: joined.json('data.membership_id') });
      }
    }
  } finally {
    for (const membership of memberships) {
      const left = http.del(`${base}/api/v1/chat-rooms/${roomId}/members/me?membership_id=${membership.id}`,
        null, params(membership.account, 'policy.leave'));
      check(left, { 'policy membership cleaned up': (response) => response.status === 204 });
    }
  }
}
export const handleSummary = summary;
