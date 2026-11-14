# YT Queue

Personal Android player for YouTube videos, organised in lists that remember exactly where you stopped.

- **Lists**: each list is its own queue (starts with *Watch later* and *Portuguese*). Switching to a list resumes its top video at the second you left it.
- **Share to add**: in the YouTube app tap Share, then *Add to YT Queue*, and pick the list.
- **Auto-play next**: when a video ends it moves to *Watched* and the next one starts.
- **Linked playlists**: link a Public or Unlisted YouTube playlist to a list; new videos are pulled in on every sync (automatic when the app opens, at most every 20 minutes).
- **Background play**: keeps playing with the screen off or in another app, with lock-screen and headphone controls.
- Rotate the phone for a full-screen player. Long-press a video to drag it; swipe to remove.

YouTube's Watch Later is private and can't be read by any app; see *About Watch Later* in the app menu for the workaround.

## Build

Every push to `main` builds a signed APK with GitHub Actions (artifact `yt-queue-apk`).
