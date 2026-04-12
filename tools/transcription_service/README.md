# SDRTrunk Local Transcription Service

This service watches `/Users/benjaminfaershtein/SDRTrunk/recordings`, transcribes new radio recordings locally, and writes indexes under `/Users/benjaminfaershtein/SDRTrunk/transcripts`.

It uses the metadata already embedded in sdrtrunk recording filenames:

```text
20260411_104743_UCSC-Campus_T-null__TO_44_FROM_7882_V6.mp3
```

From that it extracts:

```text
talkgroup = 44
radio/source = 7882
system = UCSC-Campus
site = T-null
```

## Engine

The default is `mlx-whisper` with:

```text
mlx-community/whisper-large-v3-turbo
```

That keeps transcription local on Apple Silicon through MLX. The script also has fallback support for `lightning-whisper-mlx`, `faster-whisper`, and OpenAI's local `whisper` package if you install those separately.

## One-Time Test

```sh
tools/transcription_service/run-transcriber.sh --once --limit 3
```

## Continuous Watcher

```sh
tools/transcription_service/run-transcriber.sh
```

## Outputs

```text
/Users/benjaminfaershtein/SDRTrunk/transcripts/transcripts.sqlite3
/Users/benjaminfaershtein/SDRTrunk/transcripts/transcripts.jsonl
/Users/benjaminfaershtein/SDRTrunk/transcripts/transcripts.csv
/Users/benjaminfaershtein/SDRTrunk/transcripts/by_talkgroup/TG_44.md
/Users/benjaminfaershtein/SDRTrunk/transcripts/by_radio/RADIO_7882.md
```

## LaunchAgent

To run it as a macOS user service, install the plist template:

```sh
cp tools/transcription_service/com.sdrtrunk.transcriber.plist.template ~/Library/LaunchAgents/com.sdrtrunk.transcriber.plist
launchctl bootstrap "gui/$(id -u)" ~/Library/LaunchAgents/com.sdrtrunk.transcriber.plist
launchctl enable "gui/$(id -u)/com.sdrtrunk.transcriber"
launchctl kickstart -k "gui/$(id -u)/com.sdrtrunk.transcriber"
```

To stop it:

```sh
launchctl bootout "gui/$(id -u)" ~/Library/LaunchAgents/com.sdrtrunk.transcriber.plist
```
