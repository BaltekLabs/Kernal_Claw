[app]
title = VoiceOS
package.name = voiceos
package.domain = com.balteklabs
source.dir = .
source.include_exts = py,yaml,md,json,html,css,js
source.include_patterns = src/**,web/**,skills/**,config.yaml,requirements.txt
version = 1.0.0

# Entry point: starts the FastAPI server then opens WebView
# The main.py in the vos root bootstraps the Android activity
source.main = android/main.py

requirements =
    python3,
    fastapi,
    uvicorn,
    websockets,
    aiohttp,
    psutil,
    pyyaml,
    # LLM providers (uncomment as needed):
    # anthropic,
    # openai,

orientation = portrait
fullscreen = 0
android.api = 33
android.minapi = 26
android.ndk = 25b
android.archs = arm64-v8a, armeabi-v7a
android.allow_backup = False

# Launcher intent — registers as a home screen
android.manifest.intent_filters =
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.HOME" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>

android.permissions =
    INTERNET,
    READ_EXTERNAL_STORAGE,
    WRITE_EXTERNAL_STORAGE,
    QUERY_ALL_PACKAGES,
    ACCESS_WIFI_STATE,
    RECEIVE_BOOT_COMPLETED

# App icon and splash
# icon.filename = %(source.dir)s/android/icon.png
# presplash.filename = %(source.dir)s/android/presplash.png

android.presplash_color = #000000
android.window_soft_input_mode = adjustResize

# p4a recipe extras
p4a.branch = master

[buildozer]
log_level = 2
warn_on_root = 1
