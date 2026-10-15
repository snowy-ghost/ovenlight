# The Go core calls into these through JNI, which R8 can't see.
-keep class go.** { *; }
-keep class com.snowyghost.ovenlight.core.** { *; }
-keep class * implements com.snowyghost.ovenlight.core.Platform { *; }
