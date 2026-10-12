"""Run Gradle tests against a separate disposable DB, never the benchmark data."""
import os
import subprocess
from prepare import ROOT, env_values, compose

env = env_values()
sql = b"CREATE DATABASE IF NOT EXISTS meomuneum_perf_regression; GRANT ALL ON meomuneum_perf_regression.* TO 'meomuneum_perf'@'%';\n"
compose('exec', '-T', 'mysql', 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root', input=sql)
test_env = {**os.environ, 'TEST_DB_URL': 'jdbc:mysql://127.0.0.1:13306/meomuneum_perf_regression',
            'TEST_DB_USERNAME': 'meomuneum_perf', 'TEST_DB_PASSWORD': env['PERF_DB_PASSWORD'],
            'AUTH_JWT_SECRET': env['AUTH_JWT_SECRET'],
            'RECOMMENDATION_AI_BASE_URL': 'http://127.0.0.1:18090',
            'SPEECH_TRANSCRIPTION_AI_BASE_URL': 'http://127.0.0.1:18090',
            'LOCATION_REVERSE_GEOCODING_BASE_URL': 'http://127.0.0.1:18090'}
raise SystemExit(subprocess.run(['./gradlew', 'spotlessCheck', 'checkstyleMain', 'checkstyleTest',
                                'test', 'bootJar', '--no-daemon', '--rerun-tasks'], cwd=ROOT, env=test_env).returncode)
