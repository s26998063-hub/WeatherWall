# WeatherWall

Own replacement for Samsung's "Weather" wallpaper on devices without AICore (e.g. Galaxy A26).

* **App** - downloads two small ONNX models once, builds a scene from a photo (foreground cut-out + depth haze),
  and runs it as a **live wallpaper**: tilt parallax, rain / snow / fog / clouds, time-of-day tint, weather from Open-Meteo (or manual).
* **LSPosed module** (same APK, scope `com.samsung.android.app.dressroom`) - enables the dead "Try" button on Samsung's Weather
  screen and adds a floating **Weather+** button in Dressroom that opens this app.

## Models (downloaded on first run to app-private storage)
| model | file | size | license |
|---|---|---|---|
| Depth Anything V2 Small (int8) | https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model_int8.onnx | ~27 MB | Apache-2.0 |
| U2-Net-P | https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx | ~4.6 MB | Apache-2.0 |

Other depth variants in the same HF folder: `model_fp16.onnx` (49.6 MB), `model_q4f16.onnx` (19.1 MB), `model.onnx` (99 MB).

## Build
GitHub Actions builds `app-debug.apk` on every push (Actions -> build -> artifact). Locally: `gradle assembleDebug` (Gradle 8.9, JDK 17).

## Use
1. Install the APK, open **WeatherWall**: 1) download models, 2) pick a photo, 3) set as live wallpaper.
2. Optional: LSPosed -> enable **WeatherWall**, scope **Dressroom**, force-stop Dressroom.
3. Weather: tap the mode button (Auto uses Open-Meteo + cached coarse location).

## Notes
* Legacy Xposed API (`xposedmodule`), works with LSPosed. If `api.xposed.info` is unreachable, put the `de.robv.android.xposed:api:82` jar into `app/libs` and use `compileOnly(files("libs/api-82.jar"))`.
* Not compiled yet (first build happens in GitHub Actions) - expect small fixes.
* Inspired by BlueOutside/Dressroom-Advanced (no code copied).
* Models: Depth Anything V2 (Yang et al.), U2-Net (Qin et al.), onnxruntime-android (MIT).
