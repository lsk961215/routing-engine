#!/usr/bin/env bash
exec "$(cd "$(dirname "$0")" && pwd)/servers.sh" stop "${1:-all}"
