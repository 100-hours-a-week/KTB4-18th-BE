#!/usr/bin/env python3
"""Explicit local fixture cleanup, with private full backups before any deletion."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import uuid

import fixtures

ROOT = Path(__file__).resolve().parent
PRESERVED = ['users', 'user_settings', 'terms', 'terms_agreements', 'regions', 'chat_rooms', 'flyway_schema_history']


def fingerprints():
    return {table: hashlib.sha256(fixtures.sql(f'SELECT * FROM `{table}` ORDER BY 1;').encode()).hexdigest() for table in PRESERVED}


def counts():
    return json.loads(fixtures.sql('''SELECT JSON_OBJECT(
      'users',(SELECT COUNT(*) FROM users),
      'fixture_users',(SELECT COUNT(*) FROM users WHERE id BETWEEN 91000001 AND 91001000 AND email LIKE 'chatload%@local.test' AND deleted_at IS NULL),
      'messages',(SELECT COUNT(*) FROM chat_room_messages),
      'memberships',(SELECT COUNT(*) FROM chat_room_members),
      'views',(SELECT COUNT(*) FROM chat_view_sessions),
      'intervals',(SELECT COUNT(*) FROM chat_view_intervals),
      'bans',(SELECT COUNT(*) FROM chat_bans),
      'foreign_messages',(SELECT COUNT(*) FROM chat_room_messages WHERE user_id NOT BETWEEN 91000001 AND 91001000),
      'foreign_memberships',(SELECT COUNT(*) FROM chat_room_members WHERE user_id NOT BETWEEN 91000001 AND 91001000),
      'foreign_views',(SELECT COUNT(*) FROM chat_view_sessions WHERE user_id NOT BETWEEN 91000001 AND 91001000),
      'foreign_bans',(SELECT COUNT(*) FROM chat_bans WHERE user_id NOT BETWEEN 91000001 AND 91001000));'''))


def compose(*args):
    completed = subprocess.run(['docker', 'compose', *args], cwd=ROOT, capture_output=True, text=True)
    if completed.returncode:
        raise RuntimeError('Local Compose operation failed; data and backups preserved')


def dump(path):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    error_path = path.with_suffix('.stderr')
    error_fd = os.open(error_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'wb') as output, os.fdopen(error_fd, 'wb') as error:
        result = subprocess.run(['docker', 'compose', 'exec', '-T', 'mysql', 'sh', '-c',
            'MYSQL_PWD="$MYSQL_PASSWORD" mysqldump -u "$MYSQL_USER" --default-character-set=utf8mb4 --no-tablespaces --set-gtid-purged=OFF --column-statistics=0 --single-transaction --skip-lock-tables --databases "$MYSQL_DATABASE"'],
            cwd=ROOT, stdout=output, stderr=error)
    if result.returncode or path.stat().st_size < 1000:
        raise RuntimeError('Full DB backup failed; cleanup was not allowed to proceed')
    return {'path': str(path), 'bytes': path.stat().st_size, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--restore', action='store_true', help='Explicitly restore empty-chat data baseline; preserve accounts and backups')
    args = parser.parse_args()
    if not args.restore:
        print(json.dumps({'state': fixtures.state(), 'counts': counts()}, indent=2))
        print('Read-only. Use --restore only for the explicit local baseline restoration.')
        return
    assert fixtures.sql('SELECT DATABASE();') == 'chat_load_local'
    initial = fixtures.state()
    before = counts()
    assert before['users'] == before['fixture_users'] == 1000, 'Unexpected user data; no cleanup performed'
    assert not any(before[k] for k in before if k.startswith('foreign_')), 'Non-fixture chat data; no cleanup performed'
    assert not any(initial[k] for k in ['active_memberships', 'active_views', 'open_intervals']), 'Active participation exists; no cleanup performed'
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:6]
    private_dir = ROOT / '.runtime/backups' / ('baseline-' + stamp)
    private_dir.mkdir(mode=0o700, parents=True)
    os.chmod(private_dir, 0o700)
    output = ROOT / 'results' / ('baseline-restore-' + stamp)
    output.mkdir()
    report = {'at': datetime.now(timezone.utc).isoformat(), 'database': 'chat_load_local', 'before': before,
              'status': 'started', 'previous_results_preserved': True, 'auth_store_cleared': False}
    stopped = False
    try:
        print('Stopping only this test project proxy/backend to prevent writes.', flush=True)
        compose('stop', 'proxy', 'backend')
        stopped = True
        assert counts() == before, 'Data changed during quiescing; no cleanup performed'
        preserve_before = fingerprints()
        print('Saving complete private pre-cleanup SQL backup.', flush=True)
        report['backup_before'] = dump(private_dir / 'before.sql')
        (output / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
        print('Removing only synthetic chat messages and participation/ban fixtures in one transaction.', flush=True)
        fixtures.sql('''START TRANSACTION;
        DELETE i FROM chat_view_intervals i JOIN chat_view_sessions v ON v.id=i.view_session_id
          WHERE v.user_id BETWEEN 91000001 AND 91001000;
        DELETE FROM chat_view_sessions WHERE user_id BETWEEN 91000001 AND 91001000;
        DELETE FROM chat_room_members WHERE user_id BETWEEN 91000001 AND 91001000;
        DELETE FROM chat_room_messages WHERE user_id BETWEEN 91000001 AND 91001000;
        DELETE FROM chat_bans WHERE user_id BETWEEN 91000001 AND 91001000;
        COMMIT;''')
        after = counts()
        assert after['users'] == after['fixture_users'] == 1000
        assert all(after[k] == 0 for k in ['messages', 'memberships', 'views', 'intervals', 'bans'])
        preserve_after = fingerprints()
        assert preserve_before == preserve_after, 'Preserved-table fingerprint mismatch; pre-cleanup backup retained'
        report.update({'after': after, 'preserved_table_sha256': preserve_after,
                       'preserved_tables_match': True, 'room': fixtures.room(),
                       'baseline_scope': 'Logical empty-chat baseline; accounts, terms, room settings, migration history preserved'})
        print('Saving private clean-baseline SQL snapshot.', flush=True)
        report['snapshot_clean'] = dump(private_dir / 'clean-baseline.sql')
        report['status'] = 'data_restored'
    except Exception:
        report['status'] = 'failed_or_verification_required'
        raise
    finally:
        if stopped:
            print('Starting test backend/proxy; Hazelcast and other services are preserved.', flush=True)
            try:
                compose('up', '-d', '--wait', '--wait-timeout', '120', 'backend', 'proxy')
                report['backend_restarted'] = True
            except Exception:
                report['backend_restarted'] = False
                raise
            finally:
                (output / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
        else:
            (output / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
    final = fixtures.state()
    assert final['fixture_users'] == 1000 and all(final[k] == 0 for k in ['messages', 'active_memberships', 'active_views', 'open_intervals', 'active_bans'])
    report['final_state'] = final
    report['status'] = 'restored_and_restarted'
    (output / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
    print('PASS: clean chat baseline, 1000 accounts preserved, room capacity 1000, backend restarted.', flush=True)
    print('Evidence:', output, flush=True)


if __name__ == '__main__':
    main()
