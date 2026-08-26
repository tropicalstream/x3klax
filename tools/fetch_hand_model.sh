#!/bin/sh
# Downloads the MediaPipe hand landmarker model (~7.8 MB) into assets.
# Required once before building — hand tracking will not start without it.
set -e
DIR="$(dirname "$0")/../app/src/main/assets/models"
URL="https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
echo "Fetching hand_landmarker.task ..."
mkdir -p "$DIR"
curl -fL -o "$DIR/hand_landmarker.task" "$URL"
ls -la "$DIR/hand_landmarker.task"
echo "Done."
