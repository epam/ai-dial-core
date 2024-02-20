#!/bin/sh
set -Ee

# If no args passed to `docker run`,
# then we assume the user is calling core
if [ $# -lt 1 ]; then

  # If the container is run under the root user, update the ownership of directories
  # that may be mounted as volumes to ensure 'appuser' has the necessary access rights.
  if [ "$(id -u)" = '0' ]; then
    find "$LOG_DIR" ! -user appuser -exec chown appuser '{}' +
    find "$STORAGE_DIR" ! -user appuser -exec chown appuser '{}' +

    exec su-exec appuser "/app/bin/aidial-core" "$@"
  fi

  exec "/app/bin/aidial-core" "$@"
fi

# Otherwise, we assume the user wants to run his own process,
# for example a `bash` shell to explore the container
exec "$@"