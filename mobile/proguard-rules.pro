# Rules for the phone app's own code. Library modules bring their rules with them (consumerProguardFiles):
# NewPipeExtractor/Rhino and the exception-name rule in youtube/consumer-rules.pro.

# ---- Names ending in Error or Exception ----
# youtube/consumer-rules.pro keeps the name of every Throwable, so the error screen can show the failing exception's
# class name. This module also has types named like errors that are not throwables (enums such as UpdateErrorKind do not
# end that way, but a sealed Error type could): the CI step "Check that R8 kept exception names"
# (.github/workflows/android.yml) fails when any class of com.ahmadnayfeh.shasha that ends in Error or Exception has a
# different name in mobile/build/outputs/mapping/release/mapping.txt. Names only: a class nobody uses is still removed.
-keepnames class com.ahmadnayfeh.shasha.mobile.**Error
-keepnames class com.ahmadnayfeh.shasha.mobile.**Exception

# Media3 reads some classes by name (extractors, renderers); its own consumer rules cover that. The service and the
# receivers below are declared in the manifest, which R8 keeps by itself.
