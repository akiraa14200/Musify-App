# Android / Termux local backend

Run from the repository folder inside Termux. The server binds to 127.0.0.1 only.

```sh
pkg update -y && pkg upgrade -y
pkg install -y python nodejs ffmpeg
python -m pip install --upgrade pip
pip install -r requirements.txt
npm install
cp .env.example .env
# Edit .env and add GROQ_API_KEY / GEMINI_API_KEY only if you want those providers.
npm run build
```

Start the API and UI in two Termux sessions:

```sh
uvicorn backend.main:app --host 127.0.0.1 --port 8000
```

```sh
npm run dev
```

Open http://127.0.0.1:5173 on the same phone. Data is stored in ./data. Keep this project in Termux private home (for example ~/transcript-studio), not shared storage. Android may stop background processes; reopen Termux and restart the API if needed.

Groq sends uploaded audio to Groq for transcription. Gemini receives transcript text for explicitly requested text tools. API keys are read only by the local Python process. No cloud hosting/database is used.
