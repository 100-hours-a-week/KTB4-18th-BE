#!/usr/bin/env bash
# Run against a disposable local database; never load application .env credentials.
set -euo pipefail
cd "$(dirname "$0")/.."

container="meomuneum-verify-$(date +%s)-$$"
cleanup() {
    docker rm -f "$container" >/dev/null 2>&1 || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

docker run --detach --rm --name "$container" \
    --publish 127.0.0.1::3306 \
    --env MYSQL_ROOT_PASSWORD=test-only-root \
    --env MYSQL_ROOT_HOST=% \
    --env MYSQL_DATABASE=meomuneum_test \
    --env MYSQL_USER=meomuneum_test \
    --env MYSQL_PASSWORD=test-only-user \
    mysql:9.7.0 --log-bin-trust-function-creators=1 >/dev/null

ready=false
for ((attempt = 0; attempt < 120; attempt++)); do
    if docker exec --env MYSQL_PWD=test-only-root "$container" \
        mysql --protocol=TCP --host=127.0.0.1 --user=root \
        --execute='SELECT 1' >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 1
done
if [[ "$ready" != true ]]; then
    echo 'Test MySQL did not become ready within 120 seconds.' >&2
    exit 1
fi

endpoint="$(docker port "$container" 3306/tcp)"
export TEST_DB_URL="jdbc:mysql://$endpoint/meomuneum_test?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC"
export TEST_DB_USERNAME=meomuneum_test
export TEST_DB_PASSWORD=test-only-user
export AUTH_TEST_MYSQL_ENDPOINT="$endpoint"
export AUTH_TEST_MYSQL_PASSWORD=test-only-root

if [[ $# -eq 0 ]]; then
    set -- spotlessCheck checkstyleMain checkstyleCore checkstyleChat checkstyleTest \
        test bootJar coreBootJar chatBootJar --continue
fi
./gradlew "$@" --no-daemon --rerun-tasks
