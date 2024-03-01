#!/bin/sh
set -Ee

# If no args passed to `docker run`,
# then we assume the user is calling core
if [ $# -lt 1 ]; then

  # If the container is run under the root user, update the ownership of directories
  # that may be mounted as volumes to ensure the specified user:group
  # has the necessary access rights.
  if [ "$(id -u)" = '0' ]; then

    if [ -n "$PUID" ]; then
      export UID="$PUID"
    fi

    if [ -n "$PGID" ]; then
      export GID="$PGID"
    fi

    echo "Changing the ownership of /app, $LOG_DIR and $STORAGE_DIR to $UID:$GID"
    find "/app" ! -user $UID -exec chown $UID:$GID '{}' +
    find "$LOG_DIR" ! -user $UID -exec chown $UID:$GID '{}' +
    find "$STORAGE_DIR" ! -user $UID -exec chown $UID:$GID '{}' +

    exec su-exec $UID:$GID "/app/bin/aidial-core" "$@"
  fi

  exec "/app/bin/aidial-core" "$@"
fi

# Otherwise, we assume the user wants to run his own process,
# for example a `bash` shell to explore the container
exec "$@"