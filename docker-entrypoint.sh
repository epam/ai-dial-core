#!/bin/sh
set -Ee

# If the environment variable `USE_SYSTEM_CA_CERTS` is set, the Docker container will automatically import 
# mounted private or self-signed certificates during startup. 
# The value of this variable can be any character or word. For example: 1, yes, true, YES, TRUE.
# Private or self-signed certificates must be mounted into the container in the /certificates/ directory.
if [ -x /__cacert_entrypoint.sh ]; then
  (/__cacert_entrypoint.sh)
fi

# If no args passed to `docker run`,
# then we assume the user is calling core
if [ $# -lt 1 ]; then

  # If the container is run under the root user, update the ownership of directories
  # that may be mounted as volumes to ensure 'appuser' has the necessary access rights.
  if [ "$(id -u)" = '0' ]; then
    chown -R appuser:appuser "$LOG_DIR" "$STORAGE_DIR"

    exec su-exec appuser "/app/bin/server" "$@"
  fi

  exec "/app/bin/server" "$@"
fi

# Otherwise, we assume the user wants to run his own process,
# for example a `bash` shell to explore the container
exec "$@"
