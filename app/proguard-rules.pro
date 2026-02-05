# ProGuard rules for Screen Time Tracker
# Add project specific ProGuard rules here.

# Keep line number information for debugging stack traces
-keepattributes SourceFile,LineNumberTable

# Hide the original source file name
-renamesourcefileattribute SourceFile

# Keep data classes and their fields (if you add any)
# -keep class com.screentimetracker.app.data.model.** { *; }

# Keep ViewBinding generated classes
-keep class * implements androidx.viewbinding.ViewBinding {
    public static *** bind(android.view.View);
    public static *** inflate(android.view.LayoutInflater);
}
