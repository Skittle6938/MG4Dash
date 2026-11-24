# --- R8 on published builds ---
# Shrinking and optimisation only: class and member names stay as they are, so logs, crash
# reports and everything looked up by name at runtime (hidden framework APIs, the car's
# services, policy_control...) read the same as in debug builds.
-dontobfuscate

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

# Our own classes: kept whole. The size gain comes from the libraries; our code calls itself
# by name in places (other apps' intents, enum keys, reflection on our own listeners).
-keep class app.mg4dash.** { *; }
