# VrQuest — Quest-style mixed reality for a phone in a VR Box

Android app (Kotlin, OpenGL ES 2.0, minSdk 26). Stereo split-screen + lens distortion, 3DoF head tracking,
camera passthrough, MediaPipe hand tracking (21 landmarks, 2 hands), floating windows, YouTube, games, environments.

## Build without Android Studio (phone or browser only)
1. Make a free GitHub account, create a new repository, upload ALL files of this folder (keep the `.github` folder).
2. Open the repository > Actions tab > "Build APK" > wait ~5 min until green.
3. Open the finished run > Artifacts > `VrQuest-apk` > download, unzip, install `app-debug.apk`.
If the run fails (red), open it and send the red error text to the developer.

## Build with Android Studio
1. Install Android Studio (Koala or newer). Open this folder. Let Gradle sync (creates the wrapper).
2. Build > Build APK, or in a terminal: `gradle assembleDebug` (Gradle 8.7+).
3. APK: `app/build/outputs/apk/debug/app-debug.apk`. Install it on the phone.
   Release APK (`assembleRelease`) is signed with the debug key; swap in your own keystore for distribution.

## Hand model
First start downloads `hand_landmarker.task` (about 8 MB) from storage.googleapis.com. Needs internet once.
Offline option: download it yourself and put it in `app/src/main/assets/hand_landmarker.task`.

## First test on the phone
Settings (gear in the dock) > turn off "VR split-screen" for a single flat view. Tap the screen = click at the center cursor.
If the app crashes, the next start shows a crash report: copy it and send it.
New apps: Store (web games/apps), Videos (local files), Whack-a-Mole.

## Gestures
- Point: cursor follows index fingertip. Pinch: click / drag / scroll.
- Fist on a window: grab and move it (hand away = window farther). Windows stay where you leave them.
- Drag the title bar, or the corner grip, to move / resize. Two hands pinching one window: resize like a TV.
- Title bar buttons: curve, pin (survives recenter), TV mode (huge curved screen + cinema), close.
- Poke (push one finger forward): press. Open palm 1 s: bring dock to you. Swipe open palm: next environment.
- No hand: look at a button ~1.5 s to click. Gamepad: A click, B recenter, X dock, Y passthrough, L1/R1 environment.

## Honest limits
- Never compiled or run by the author (no Android SDK available). Expect to fix a few compile errors and tune values.
- 3DoF only. Windows are fixed in direction, not in position: walking does not move you past them (needs ARCore for that).
- Passthrough is a single 2D camera image. Hand depth is estimated from hand size, so poke and "push away" are approximate.
- If passthrough looks rotated or mirrored: Settings > Mirror camera, Camera FOV. Lens values: Settings > k1, k2, IPD, lens offset.
- YouTube runs as the desktop website in a WebView. Google sign-in may be refused in WebViews; videos play without login.
  Fullscreen button does nothing: use the TV button on the window. No 360/SBS YouTube modes.
- App drawer launches Android apps normally (leaves VR). Real apps cannot be put into floating windows without system privileges.
- Gallery: photos only. "360° VR" shows an equirectangular photo as the whole environment.
- Not included: screen recording/casting, phone-as-controller mode, store tab, file manager.
