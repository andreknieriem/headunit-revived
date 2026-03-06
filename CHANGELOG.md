# Changelog
### v.1.15.1
- New Feature: Added **Auto-Optimization Wizard** to automatically find the best Resolution, DPI, and Codec settings for your hardware.
- Bugfix: Fixed **Self Mode** failing to start in offline/hotspot scenarios (Network ID 0 fix).
- Bugfix: Improved **Audio Routing**. The phone is now more likely to route audio to the headunit immediately upon connection by using an early-initialized MediaSession with remote playback metadata.
- Bugfix: Fixed **GPS Speed** calculation. Speeds were previously doubled due to an incorrect unit conversion (knots instead of mm/s).
- UI: Improved Settings readability on small screens by allowing multi-line descriptions.

### v.1.15.0
- Added arabic language thanks to A5H0
- Added new intent for setting day/night mode for maps
- Added new window flags for older devices to finally fix fullscreen issues
- Added new intents to make the headunit recognize the app as navigation app
- Added LegacyOptimizer which will handle things directly and faster for single core cpus. Should improve the performance on Android 4.1 - 4.4 Devices
- Fixed BT Permission Bug
- Changed the Twilight-Calculator for better switch to day/night on auto mode to prevent to bright display
- Added more mediasession logic to gain audio focus and audio routing
- Merged Retry Button on connect screen, thanks to @andrecuellar
- Merged auto connect usb feature, thanks to @andrecuellar

### v.1.14.3
- New Feature: Added **App Shortcuts** for quick Connect/Disconnect. Now compatible with Samsung Modes & Routines.
- New Feature: Registered HURev as an official system navigation provider (Category: Maps).
- Improvement: Added `headunit://disconnect` intent for remote session termination.
- Fixed: Resolved potential app freezes during long-running sessions by optimizing memory hygiene and FIFO handling.
- Compatibility: Improved support for Amazon Fire Tablets and devices without GPS hardware by marking hardware features as optional.

### v.1.14.2
- Bugfix: Resolved `SuperNotCalledException` crash in MainActivity.
- Bugfix: Notification and Exit Button do not close the app
- Improvement: Removed old legacy Invisible Bluetooth Setting to prevent Bluetooth from start on the whole time
- Localization: Corrected "Version" translations in multiple languages.

### v.1.14.1
- Improvement: Integrated USB Auto-Connect into "Auto-Connect Last Session". App now behaves like a native headunit and connects automatically on startup or USB plug-in.
- New Feature: Added USB Soft-Reset logic. Automatic recovery from USB "stalls" without needing to replug the cable.
- Major Improvement: Audio focus and routing overhaul. Added `MediaSession` support and immediate focus response to phone. Fixes issues where background apps on the tablet would block Android Auto audio.
- Improvement: Robust Task Switching. Leaving the app via Home button or clicking the Launcher icon no longer breaks the connection. Music continues in background, and clicking the icon/notification correctly returns to the projection.
- New Feature: Enhanced Key Debugger ("Key-Sniffer"). Prominent display of all key events, including special characters (ö, ü, ß) and proprietary steering wheel intents (MTC, FYT).
- New Feature: Official Navigation App Registration. HURev is now recognized as a navigation provider (`geo:`, `google.navigation:`, `android.intent.action.NAVIGATE`). Compatible with hardware "NAV" buttons.
- Bugfix: Removed redundant "Already connected" and "Reconnection required" alerts for a smoother user experience.
- Localization: All new strings translated into 10 languages.

### v.1.14.0
- Added Separate volume setting #91
- Added Auto-Start on Bluetooth Option
- Merged PR #134 - Fixing Connection on Mediathek Headunits
- Merged PR #131 - Fixes SystemUI on < Android 6 Devices
- Merged PR #127 - Fixing Audio Truncation

### v.1.13.3
- Fixed Screen Issues on Android 4 with header and navigations #114
- Fixed Night-Mode Bug #116
- Merged several PR for better Language Handling with a new language selector. Thanks to @andrecuellar

### v.1.13.2
- Fixed margins now working for devices prior Android 5 Lollipop
- Fixing warnings
- Fixing broken colors on mixed daynight values
- Fixed a bug where a message is bigger than thought after about 20 minutes and connections closes

### v.1.13.1
- Fixed Custom Insets Dialog with a Scrollview
- Fixed 4 app crashes listed in play console
- Fixed 2 warnings in play console for edge-to-edge display
- Fixed a race condition in ssl read/write
- Preventing disconnect if just one package was broken/corrupt in ssl transfer

### v.1.13.0
- Improvement: USB stability overhaul (implemented 16KB internal buffer)
- New Feature: Custom Insets (Margins) setting with live preview
- Fixed: Video decoder blackscreen on some AI-Boxes (H.264 NAL padding)
- Fixed: UI focus issues in Settings causing system bars to reappear
- Fixed: Native SIGABRT crashes during reconnection
- Cleaned up Debug settings

### v.1.12.0
- Major Improvement: Wireless Connectivity overhaul (Socket Reuse, better Handshake)
- New Feature: Wireless Mode Switch (Manual, Auto-Scan, Wireless Helper Support)
- Added: Support for Wireless Helper companion app
- Fixed: Android 15 (16KB page size) compatibility for native libraries

### v.1.11.1
- Improvement: 1440p and h265 are now checked both. Some old devices have more than 1080p but no h265 support and android auto crashes with Error 11
- Fixed bug in Kitkat Devices on search for wireless devices
- Merged PR #94 - Fixed blurry icon. Thanks to @nicoruy
- Merged PR #95 - Make Settings own View to apply directly. Thanks to @nicoruy

### v.1.11.0
- New Feature: Advanced Night Mode (Light Sensor, Screen Brightness, separate thresholds, manual time)
- Improvement: Audio Stuttering fixed (Optimized ACK handling)
- Improvement: USB Reconnection stability (Added "Reconnection Required" dialog for stuck sessions)
- Improvement: WiFi Discovery (Added Multi-Interface Scan and NSD/mDNS support)
- New Feature: Enhanced Service Notification (Reduced noise, added Exit button)
- Added: Spanish translation 🇪🇸 thanks to @andrecuellar
- Added: Ukraine translation 🇺🇦 thanks to welshi32
- Bugfix: Non-Fullscreen View was stretched, touch could be off
- Bugfix: Wifi with Headunit Server now works with hotspot

### v.1.10.4
- Added: Dutch translation 🇳🇱 thanks to safariking
- Several black screen and connection error enhancements
- Bugfix: Crash in Background if not started as foreground service

### v.1.10.3
- Bugfix: Force Software Decoder wasn't getting always the sw decoder
- Added: Russian translation 🇷🇺 thanks to @prostozema
- Enhancement: Fixing small issues in the video-decoder which should help lower spec devices to render properly (but act a little bit slower perhaps)

### v.1.10.2
- Bugfix - Button Mapping ignored #71
- New Feature: Screen-Orientation Feature to lock to a certain orientation (Landscape/Portrait) #69 thanks to @JanRi3D
- Enhancement: SSL will now attempt multiple times and not break instantly thanks to @MicaelJarniac
- Added: Chinese(Traditional) translation 🇹🇼 thanks to @GazCore
- Added: Polish translation 🇵🇱 thanks to @Kacper1263
- Added: Czech translation 🇨🇿 thanks to @teodortomas
- Fixed brazilian portuguese folder name

### v.1.10.1
- Bugfix: Added missing 3 Byte startcode which stops some devices to start the projection
- Added PR #68 - Fix Wifi Direct detection thanks to @rakshan-kumr
- Added PR #67 - Brazilian Portuguese translation 🇧🇷 thanks to @MicaelJarniac
- Added PR #66 - Add conscrypt to fix error 7 on lower Android versions 🚀, thanks to @JanRi3D
- The old jni files and c code can maybe be removed when PR #66 is performing great. So we can get rid of that again :)

### v.1.10.0
- New Feature: Portrait Mode Support (Dashboard & Projection) with smart resolution scaling Known Bug is, that map is unresponsive to touch. That is in all HU apps
- New Feature: Redesigned Keymap Screen (easier configuration)
- New Feature: Right Hand side driving setting (#63)
- New Feature: Auto-Connect last session (Thanks to @JanRi3D) (#21)
- New Feature: Auto-Selfmode if enabled in settings
- New Feature: Allow sideloaded apps (#57)
- Localization: Added German Translation 🇩🇪 Other translations are highly appreciated
- Improvement: TextureView is now the default renderer (better compatibility for most devices)
- Improvement: Fixed Dashboard layout rotation
- Rewrite: Completly Rewrite the Video-Decoder as it was undebuggable. Removed the async mode and more

### v.1.9.0
- New Feature: GLES20 Video Renderer (Fixes black screen/artifacts/scaling on older Head Units)
- New Feature: In-App Log Export (Save to file/Share) for easier debugging
- Improvement: Audio Sink Logic fixed (System Audio always advertised) -> Fixes black screen when Audio Sink is disabled
- Improvement: Video Decoder optimized for legacy devices (Buffer size adjustments, Overflow handling)
- Hopefully Fix: Audio Stuttering resolved (Buffer/Queue logic reverted to stable state)
- Fix: Video Fragmentation on some devices (Support for split frames/Offset 2 headers)
- Fix: Crash on Android 5.1 (NoSuchMethodError)
- Fix: Audio-Sink disable not working
- UI: Consistent Dialog Theme (Teal/Rounded) and improved list buttons
- Compatibility: Verified support for Android 4.1+ (minSdk 16)
- Compatibility: Bring back native SSL Support (JNI) for better performance on older devices (Toggle in Debug Settings)

### v.1.7.0
- Added WiFi Network Discovery (Port Scan) with Auto-Connect
- Added Intent Support (`headunit://connect?ip=...`) for automation
- Added Wifi-Launcher Support with new setting
- Updated README

### v.1.6.3
- Added mandatory Safety Disclaimer on first start
- Improved audio stability and fixed stuttering issues
- Enhanced full-screen stability with transparent system bars
- Fixed WiFi disconnection synchronization issues (ByeBye request)
- General UI and stability improvements

### v.1.6.2
- Fixed critical screen flickering during startup and fullscreen transitions
- Resolved video decoder freezing issues on tablets and older devices
- Improved system bar handling for a more stable projection experience

### v.1.6.1
- Added "About" page with version info, changelog, and license
- Added "Force Legacy Decoder" (synchronous mode) setting to fix issues on some devices (e.g., Mediatek)
- Improved surface handling to prevent crashes on decoder reconfiguration
- Fixed "Unsaved changes" dialog in settings
- Updated UI with consistent back arrows and theming
- Fixed black screen issues on some devices by optimizing decoder initialization

### v.1.6.0
- Fixed the selfmode not working outside the wifi bug
- Redesign of App, Look and feel with modern Material 3
- Better Settings
- Huge Android Auto Protocol Updates
- Clicking Exit in Android Auto now closes the projection

### v.1.5.0
- Complete Rewrite of the Video decoder for better Video-Performance
- Updated Android-Auto Protocol with the latest available codecs (h265 for example)
- Added 1440p Video-Option(Note this only works with h265!)
- Added FPS-Setting
- Added Codec-Setting
- Added Force to Use Software decoder Setting
- Merged the Android Native SSL Library and get rid of the old jni files

### v.1.4.1
- Fixing Touch-Events for devices with higher resolutions
- Removing file-log and logging is only enabled if debug is on

### v.1.4.0
- Added Selfmode
- Better Close App

### v.1.3.0
- Changed the Settings Layout Look and feel
- Added DPI Option
- Added full screen option
- Fixing Keymap Changes and button recognition

### v.1.2.1 - Resolution enhancement
- Just a minor enhancement for the resolution. Not yet perfect in my opinion but better than before
- The is the last release for this year. Happy Holidays to all and a happy new year

### v.1.2.0 - Bugfix Release
- Added Exit button to app
- Added resolution settings back for better compatibility with different screen sizes
- Added Option for which texture to use. Some devices perform better on SurfaceView, some on TextureView
- Fixed keymapping
- Fixed a lot of color issues
- Fixed a bug where the app crashed on startup on some devices
- Fixed Layout on wider screens
- Some rewrite, and small bugfixes

### v1.1.0 - New Design
- Changed the basic design to a modern look and bigger buttons
- Hopefully fixed audio-stutters with audio thread and some logs
- Removed some deprecations

### v1.0.0 - Initial Revived Release
- Updated dependencies to latest versions.
- Improved compatibility with newer Android versions.
- Added Multitouch-Support
- Some sort of wireless support with Headunit-Server on Phone
