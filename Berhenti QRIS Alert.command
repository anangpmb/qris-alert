#!/bin/bash
cd "$(dirname "$0")" || exit 1
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

NODE="node"
[ -x "./runtime/node" ] && NODE="./runtime/node"

"$NODE" server/server.js --stop
sleep 2
