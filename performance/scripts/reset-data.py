"""Explicit reset for the disposable benchmark database; never called during preparation."""
import argparse
from prepare import compose, env_values

parser = argparse.ArgumentParser()
parser.add_argument('--confirm-disposable-db', action='store_true', required=True)
parser.parse_args()
env_values()
running = compose('ps', '--status', 'running', '--services', capture_output=True).stdout.decode().split()
if 'backend' in running:
    raise SystemExit('Stop the performance backend first to clear its in-memory state and avoid concurrent writes.')
sql = b'''START TRANSACTION;
DELETE FROM recommendation_items;
DELETE FROM recommendation_sessions;
DELETE FROM chat_room_messages;
DELETE FROM chat_room_members;
COMMIT;
'''
compose('exec', '-T', 'mysql', 'sh', '-c',
        '[ "$MYSQL_DATABASE" = meomuneum_perf ] && MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u "$MYSQL_USER" "$MYSQL_DATABASE"',
        input=sql)
print('Cleared only recommendation and chat run data in the dedicated performance database. Evidence preserved.')
