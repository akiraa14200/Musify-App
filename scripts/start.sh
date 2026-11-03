#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$(dirname "$0")/.."
if [ ! -d node_modules ]; then npm install; fi
if [ ! -d .venv ]; then python -m venv .venv; fi
source .venv/bin/activate
pip install -r requirements.txt
echo "Start the API in this session with:"
echo "uvicorn backend.main:app --host 127.0.0.1 --port 8000"
echo "Then run npm run dev in a second Termux session."
