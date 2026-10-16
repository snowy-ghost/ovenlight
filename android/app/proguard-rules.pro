# The Go core calls the app's Platform through JNI, which R8 can't see. Its own classes
# are kept by the rules in its library (scripts/build-android-core.sh).
-keep class * implements com.snowyghost.ovenlight.core.Platform { *; }
