# App-specific R8 rules. AndroidX / Compose libraries ship their own consumer
# rules, and manifest components (activities, services) are kept automatically.

# Strip verbose/debug logging from release builds (warnings and errors are kept).
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Keep line numbers for readable crash reports; upload
# app/build/outputs/mapping/release/mapping.txt to Play Console with each release.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
