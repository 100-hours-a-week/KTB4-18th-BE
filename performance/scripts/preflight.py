"""Read-only readiness checks; does not submit recommendations or chat messages."""
import json
import urllib.request
import urllib.error
import time
from prepare import compose, env_values


def get(url):
    with urllib.request.urlopen(url, timeout=5) as response:
        return json.loads(response.read())


def main():
    env_values()
    health = json.loads(compose('exec', '-T', 'backend', 'curl', '-fsS',
                               'http://localhost:9091/actuator/health', capture_output=True).stdout)
    assert health['status'] == 'UP', 'Backend not healthy'
    metrics = compose('exec', '-T', 'backend', 'curl', '-fsS',
                      'http://localhost:9091/actuator/prometheus', capture_output=True).stdout.decode()
    for name in ('performance_recommendation_queue_size', 'performance_chat_lock_wait_seconds',
                 'jvm_memory_used_bytes', 'hikaricp_connections_active'):
        assert name in metrics, 'Missing metric: ' + name
    for attempt in range(11):
        targets = get('http://127.0.0.1:19090/api/v1/targets')['data']['activeTargets']
        if {target['labels']['job'] for target in targets if target['health'] == 'up'} >= {'backend', 'mock'}:
            break
        if attempt == 10:
            raise AssertionError('Scrape not ready after 20 seconds')
        print('Waiting for Prometheus to scrape the restarted backend.', flush=True)
        time.sleep(2)
    assert get('http://127.0.0.1:13000/api/health')['database'] == 'ok'
    config = get('http://127.0.0.1:18090/test/config')
    assert config['MOCK_MODE'] == 'normal', 'Normal baseline needs MOCK_MODE=normal'
    for path in ('/api/v1/recommendations/1', '/actuator/prometheus'):
        try:
            get('http://127.0.0.1:18080' + path)
            raise AssertionError('API listener must reject anonymous access: ' + path)
        except urllib.error.HTTPError as error:
            assert error.code == 401, 'Unexpected API security response'
    print('Backend, metrics, Prometheus targets, Grafana and normal mock readiness passed. No workload submitted.')


if __name__ == '__main__':
    main()
