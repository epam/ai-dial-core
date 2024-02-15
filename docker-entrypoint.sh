#!/bin/sh
set -Ee

# If no args passed to `docker run` or first argument start with `--`,
# then the user is passing arguments to the core
if [ $# -lt 1 ] || echo "$1" | grep -qE '^--'; then
  user=$(id -u)

  if [ "$user" = '0' ]; then
    find "$LOG_DIR" ! -user appuser -exec chown appuser '{}' +
    exec su-exec appuser "/app/bin/aidial-core" "$@"
  fi

  exec "/app/bin/aidial-core" "$@"

# Otherwise, we assume the user wants to run his own process,
# for example a `bash` shell to explore this image
exec "$@"