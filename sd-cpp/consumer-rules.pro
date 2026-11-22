# Consumer rules for :sd-cpp — the JNI contract R8 cannot see.
#
# sdcpp_jni.cpp resolves SdRaster by string at class-load time:
#   FindClass("me/rerere/sdcpp/SdRaster") + GetMethodID("<init>", "(III[B)V")
# and constructs every result image through that cached ctor. Nothing in Kotlin
# ever names the ctor, so the release shrinker (AGP 9 `optimization { enable = true }`
# in app/build.gradle.kts — NOT minifyEnabled, which is why greps for it lie) pruned
# the class to an empty class_def: on the Fold the ctor lookup returned null,
# JNI_OnLoad returned JNI_ERR, and the first generation crashed the app
# (UnsatisfiedLinkError, v0.0.4 line). The class NAME must also survive — FindClass
# takes a literal string, so plain member-keeps with obfuscation would break too.
#
# Kept narrow on purpose: SdProgressSink needs no rule — native code reaches it via
# GetObjectClass on the instance llama-style, and R8 renames interfaces consistently.
-keep class me.rerere.sdcpp.SdRaster { <init>(...); }
