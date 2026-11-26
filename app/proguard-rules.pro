# --- R8 on published builds ---
# Shrunk, optimised and renamed (26/11: 1.1 MB less code). What is looked up by name stays named:
# the manifest's and layouts' classes (aapt adds those), the rules below. A crash report is read
# with the build's mapping.txt (kept with its workflow run).

# Keep the line numbers of crash reports
-keepattributes SourceFile, LineNumberTable, Signature, InnerClasses, EnclosingMethod, *Annotation*

# Conscrypt's native code calls back into its Java classes by name
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**

# osmdroid reads its tile sources and some classes by name
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Our enums: their names are saved in settings and JSON (version, chrono runs, cards...)
-keepclassmembers enum app.mg4dash.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# The SAIC classes we rebuild: sent to the car's services over binders, by their class name
-keep class com.saicmotor.** { *; }
# Read by name
-keep class app.mg4dash.BuildConfig { *; }
-keepclassmembers class app.mg4dash.** { @android.webkit.JavascriptInterface <methods>; }
# Kotlin companion objects' names appear in reflection-free code only; nothing else of ours is looked up by name
