# Proguard rules for Naked Browser (Max shrinkage)
-keepattributes *Annotation*
-dontwarn android.webkit.**
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
