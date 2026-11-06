# iBoard 1.0.44

Built from commit 28462db (version code 44).

- Translator: translate icon (A/文) in the icon capsule. Pick the languages on the bar, type, and the
  translation is written into the app as you type; Return keeps it. 59 languages on the device
  (Google ML Kit, each language downloaded once).
- Online dictation with live text, through the phone's speech service. Settings › Dictation Engine:
  Automatic (online when connected, offline Whisper otherwise), Online, Offline.
- Improve Offline Dictation: names and rare words from online dictation are remembered on the phone
  and given to the offline Whisper model as context.
- Icon capsule widened to six buttons.

The signed AAB is here. The universal APK (111 MB, all CPU types) is over GitHub's 100 MB file limit
and is delivered separately; Play installs only the phone's own CPU type, so downloads are much smaller.

Signed with the Play upload key (alias `upload`).
