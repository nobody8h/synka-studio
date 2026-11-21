# Synka Studio ProGuard / R8 Rules

# Keep Shizuku IPC and LSPosed HiddenApiBypass classes for reflective Binder calls
-keep class rikka.shizuku.** { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }
