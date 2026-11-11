#!/usr/bin/env bash
# 프론트엔드(Vite, 9201) 개발 서버. 처음에는 npm install 도 한다.
set -euo pipefail
cd "$(dirname "$0")/../frontend"
[[ -d node_modules ]] || npm install
exec npm run dev
