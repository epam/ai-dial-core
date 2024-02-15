#!/bin/sh
set -Eeo pipefail

user=$(id -u)

if [ "$1" = '/app/bin/aidial-core' ] && [ "$user" = '0' ]; then
  find . \! -user appuser -exec chown appuser '{}' +
  exec su-exec appuser "$@"
else
  exec "$@"
fi