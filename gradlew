#!/bin/sh
DIR=$(cd "$(dirname "$0")" && pwd)
GR=/home/jmeisburg/.gradle/wrapper/dists/gradle-8.14.3-all/10utluxaxniiv4wxiphsi49nj/gradle-8.14.3
exec "$GR/bin/gradle" "$@"
