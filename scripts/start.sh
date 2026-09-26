#!/usr/bin/env bash
exec "$(cd "$(dirname "$0")" && pwd)/servers.sh" start "${1:-all}"
