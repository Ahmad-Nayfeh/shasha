# Rules :remote brings to the app's R8 run (checked with R8 against the release runtime classpath).

# Ktor looks for a JVM debugger through java.lang.management, which Android does not have. The call is
# wrapped in a catch-all, so on Android it simply answers "no debugger"; R8 only has to stop failing on it.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
