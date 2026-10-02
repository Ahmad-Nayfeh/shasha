# Rules for the app's own code. Library modules bring their rules with them (consumerProguardFiles):
# NewPipeExtractor/Rhino and the exception-name rule in youtube/consumer-rules.pro, Ktor in
# remote/consumer-rules.pro.

# ---- Names ending in Error or Exception ----
# youtube/consumer-rules.pro keeps the name of every Throwable. This app also has types that are named like errors
# but are not throwables (ApiError in :remote, the enum VoiceError): R8 renamed them, and the CI step "Check that R8
# kept exception names" (.github/workflows/android.yml) fails when any class of com.ahmadnayfeh.shasha that ends in
# Error or Exception has a different name in app/build/outputs/mapping/release/mapping.txt. Names only: a class
# nobody uses is still removed.
-keepnames class com.ahmadnayfeh.shasha.**Error
-keepnames class com.ahmadnayfeh.shasha.**Exception
