# The player is shrunk by R8 (release). Only what is reached through reflection / native code must be kept.

# JNA looks up its own classes and the Callback / Library interfaces by reflection.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# libscripts.so calls back into these (ScriptsLib, the Cb* callback interfaces, JnaScriptHost).
-keep class com.nebula.studio.runtime.** { *; }
