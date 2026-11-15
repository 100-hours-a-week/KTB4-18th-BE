#!/usr/bin/env python3
"""Run backend checks against a disposable MySQL, without inherited app configuration.

Usage: python3 scripts/verify-backend.py [Gradle tasks and options]
Docker must be running. Only the container created by this invocation is removed.
"""
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
PREFIXES = ("AUTH_", "CHAT_", "CORE_", "SPRING_", "LOCATION_", "RECOMMENDATION_", "SPEECH_", "TEST_", "DEV_", "MUSIC_SIGNUP_")
DEFAULT_TASKS = ["spotlessCheck", "checkstyleMain", "checkstyleCore", "checkstyleChat", "checkstyleTest",
                 "test", "bootJar", "coreBootJar", "chatBootJar"]

def main():
    name = "meomuneum-verify-" + secrets.token_hex(6)
    password = secrets.token_hex(24)
    created = False
    try:
        subprocess.run(["docker", "run", "--detach", "--name", name,
                        "--publish", "127.0.0.1::3306", "--env", "MYSQL_ROOT_HOST=%",
                        "--env", "MYSQL_ROOT_PASSWORD=" + password, "--env", "MYSQL_DATABASE=meomuneum_test",
                        "--env", "MYSQL_USER=meomuneum_test", "--env", "MYSQL_PASSWORD=" + password,
                        "mysql:9.7.0", "--skip-log-bin"], check=True, stdout=subprocess.DEVNULL)
        created = True
        endpoint = subprocess.check_output(["docker", "port", name, "3306/tcp"], text=True).strip()
        deadline = time.monotonic() + 120
        while True:
            probe = subprocess.run(["docker", "exec", name, "mysql", "-uroot", "-p" + password,
                                    "-Nse", "SELECT 1"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if probe.returncode == 0:
                break
            if time.monotonic() >= deadline:
                raise RuntimeError("isolated MySQL did not become ready within 120 seconds")
            time.sleep(1)
        test_inputs = {"CHAT_RECOVERY_FE_DIR", "CHAT_BROWSER_FE_DIR", "SPEECH_AUDIO_FFMPEG_PATH"}
        env = {key: value for key, value in os.environ.items()
               if not key.startswith(PREFIXES) or key in test_inputs}
        env.update({"TEST_DB_URL": "jdbc:mysql://" + endpoint + "/meomuneum_test?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC",
                    "TEST_DB_USERNAME": "meomuneum_test", "TEST_DB_PASSWORD": password,
                    "AUTH_TEST_MYSQL_ENDPOINT": endpoint, "AUTH_TEST_MYSQL_PASSWORD": password,
                    "AUTH_JWT_SECRET": "isolated-only-jwt-secret-at-least-32-bytes",
                    "AUTH_STATE_HMAC_KEY": "isolated-only-state-secret-at-least-32-bytes",
                    "AUTH_STATE_REPLAY_ENCRYPTION_KEY": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                    "RECOMMENDATION_AI_BASE_URL": "http://127.0.0.1:1",
                    "SPEECH_TRANSCRIPTION_AI_BASE_URL": "http://127.0.0.1:1",
                    "LOCATION_REVERSE_GEOCODING_BASE_URL": "http://127.0.0.1:1"})
        args = sys.argv[1:] or DEFAULT_TASKS
        if args[:1] == ["--"]:
            args = args[1:]
        if "--no-daemon" not in args:
            args.append("--no-daemon")
        directory = ROOT / "build/full-test-verification"
        directory.mkdir(parents=True, exist_ok=True)
        print("Running checks with isolated MySQL and application environment.", flush=True)
        with (directory / "latest.log").open("w") as log:
            process = subprocess.Popen([str(ROOT / "gradlew"), *args], cwd=ROOT, env=env,
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            try:
                for line in process.stdout:
                    log.write(line)
                    log.flush()
                    sys.stdout.write(line)
                    sys.stdout.flush()
                return process.wait()
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=30)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
    finally:
        if created:
            subprocess.run(["docker", "rm", "--force", name], check=False, stdout=subprocess.DEVNULL)

if __name__ == "__main__":
    raise SystemExit(main())
