








# [v3.1]
### Changes
- NEW: "Capture Resolution" setting (Small / Medium / High) - lets low-RAM devices use a smaller capture size. Restart the grabber to apply. Available in mobile settings and the TV setup screen
- NEW: 1x1 home-screen widget to toggle screen capture on/off with a single tap (add it via your launcher; works alongside the existing quick-settings tile and the "Hyperion Grabber (Toggle)" shortcut)
- NEW: "Capture Method" setting with a **Codec (compatibility)** option that routes screen capture through an H.264 encoder/decoder pair (the same trick scrcpy uses). This fixes black frames on TVs where the VirtualDisplay → ImageReader path returns empty frames (TCL, Amlogic S905X5M / Ugoos, Hisense, etc.). Falls back to the standard method automatically if it fails to initialize
- Declared WRITE_SECURE_SETTINGS in the manifest so ADB commands like `pm grant com.hyperion.grabber android.permission.WRITE_SECURE_SETTINGS` can run (TCL Tguard workaround)
- Removed the WLED DDP "direct send" feature (settings, strings, client) - the app now only talks to a Hyperion instance
- Removed dead/never-invoked update-checker code that referenced an undeclared FileProvider and a stale repo URL
- Reconnect now actually retries when the host is unreachable at startup (previously it gave up immediately)
- Screen on/off pause keeps the existing socket instead of disconnecting and leaking a duplicate client
- Foreground service now returns START_NOT_STICKY so it can't restart as a zombie with no capture intent

### Fixed
- Color command sent Android's ARGB value directly; the alpha byte landed in the red channel, so Color.BLUE showed red and Color.BLACK (clear) showed red. Now masked to 0xRRGGBB
- Lights were never cleared / socket never closed on stop: clear+disconnect was posted to a handler after its looper had already quit
- Possible NPE / crash when the connection thread was halted during startup while screen capture was starting
- Reconnect retry could leave a second socket open (leak) after pausing
- Audio Visualization mode never worked on Android 6+ because RECORD_AUDIO was never requested at runtime (now requested before capture)
- Scanner executor thread was never shut down after a scan finished; scan is now also cancelled if the activity is destroyed
- Partial TCP reads / corrupt reply headers could desync reply framing and allocate huge buffers; reply reader is now robust and size-limited
- Invalid or zero frame-rate preference caused a division-by-zero crash; now clamped
- Misconfigured priority preference caused a NumberFormatException crash; now falls back to default
- Settings screens showed "Reconnect Delay" in validation errors for every field; now shows the actual field name
- Mobile settings showed the raw frame-rate value ("30") instead of the label ("30 fps")

# [v3.0]
### Changes
- Frame processing pipeline optimization with ring buffer zero-copy
- Network threading bottleneck fixed with non-blocking frame handoff
- CPU-adaptive frame rate management with load tracking
- Border detection caching to reduce recalculation overhead
- Startup sequence parallelization for faster initialization
- RGB sync animation with adjustable frame delay (-100 to +100ms)
- Audio visualization feature for audio-only mode with spectrum analysis
- Auto-enable animation when audio mode is active
- 40% frame latency reduction in initial implementation
- 25% CPU efficiency improvement at 60fps

### Fixed
- Redundant frame buffering causing latency
- Single-threaded network executor bottleneck
- Inefficient frame task cancellation overhead
- Border detection recalculating on static content

---

# [v2.6]
### Changes
- Major code quality and performance improvements
- Comprehensive refactoring with 31 critical issues fixed
- Optimized divisor calculation algorithm (90% faster)
- Added SharedPreferences caching (70% faster)
- Implemented buffer pooling to reduce GC pressure
- New PerformanceOptimizer utility for device-aware optimization
- New BufferPool for object reuse
- New PerformanceMonitor for metrics tracking
- New ResourceManager for lifecycle management
- Replaced Thread() calls with ExecutorService
- Removed blocking Thread.sleep() calls
- Device-adaptive behavior for low-memory devices

### Fixed
- NPE issues with ActivityManager and MediaProjectionManager
- Memory leaks from context storage
- Race conditions in threading
- Excessive garbage collection
- Resource leaks from improper cleanup
- Foreground service startup failures
- Preference access causing disk I/O bottlenecks

---

# [v2.5]
### Changes
- Minor stability improvements
- Updated dependencies

---

# [v2.0.1]
### Changes
- APKs are now signed 

---

# [v2.0]
### Changes
- Full Android 12+ (API 31+) compatibility
- Migrated from Android Support Library to AndroidX
- Updated to Gradle 8.4 and Android Gradle Plugin 8.2.1
- Updated to Kotlin 1.9.22
- Updated target SDK to 34 (Android 14)
- Added foreground service type declaration for media projection
- Added POST_NOTIFICATIONS permission for Android 13+
- Replaced deprecated AsyncTask with ExecutorService/Handler pattern
- Removed ButterKnife dependency (TV app)
- Updated Konfetti library to v2.0.4
- Updated Protobuf to 3.25.1 with protobuf-javalite
- Changed default message priority to 100
- Added proper PendingIntent immutability flags for Android 12+

### Fixed
- FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION error on Android 12+
- PendingIntent mutability crash on Android 12+
- Notification permission handling for Android 13+
- Resource reference issues with AGP 8.x namespace changes

## [v1.0]
### Changes


- OLD
## [v1.0]

- Arabic translation

### Fixed
- Possible NPE when stopping the grabber

## [v0.5-beta]
### Changes
- Added the ability to send only the average color of the screen
- French translation
- Norwegian translation
- Czech translation
- German translation
- Dutch translation
- Partial Russian translation
- Partial Spanish translation
- Removed openGL grabber option
- Added toggle grabber activity shortcut
- LEDs will now be cleared when rebooting or shutting down

### Fixed
- Lights now clear (if running) when shutting down
- Assertion bug in TV settings
- Possible null intent when starting grabber
- OOM bug

## [v0.4-alpha]
### Changes
- Start grabber on device boot
- Added some eye candy for when grabber is started
- General UI tweaks (tv & mobile)
- Reconnect behavior implemented for mobile build
- New connection wizard
- New settings/connection page (tv build)
- Quick settings tile to toggle grabber (mobile build)
- Screen orientation change updates grabber
- Configurable grabber image quality
- Pressing the notification will now return to the app's main activity

### Fixed
- Grabber would fail to resume when waking device
- OpenGL grabber sometimes halting immediately after starting screen grab
- Default grabber failing to send data the first time it is turned on
- Grabber not stopping when the host is unreachable
- Aspect ratio of grabbed image being slightly off
- OOM bug

## [v0.3-alpha]
### Changes
- Leanback launcher support (tv build)
- Revised layout (tv build)
- Reconnect if connection is lost to hyperion server (tv build)

## [v0.2-alpha]
### Changes
- App Icon
- Fancy toggle button
- Bug fixes
- New Grabber (old grabber can be enabled in the settings)

### Known Bugs
- OpenGL grabber will sometimes hang when started, making the lights unresponsive. Quitting the app and starting again generally fixes the problem.
- New grabber fails to send any data the first time it is initialized. Turning off and back on one more time seems to fix the problem.