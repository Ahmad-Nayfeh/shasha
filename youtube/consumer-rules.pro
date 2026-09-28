# Shrinker rules for everything this module brings in. They are applied to any app that depends on
# :youtube, so swapping or upgrading the extraction library means editing this module only.

# ---- NewPipeExtractor ----
# Rhino runs YouTube's player JavaScript (signature / n-parameter deobfuscation).
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-keep class org.mozilla.javascript.engine.** { *; }
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
# The "3 days ago" parsers are loaded by class name, one per language.
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
# protobuf-javalite (YouTube request parameters) reads message fields reflectively.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn java.beans.**

# ---- Error names ----
# The unified error screen shows the failing exception's class name (SourceException.technicalName,
# e.g. "ContentNotAvailableException"): keep the names of all exceptions, whichever library throws
# them. Names only: unused exception classes are still removed.
-keepnames class * extends java.lang.Throwable
