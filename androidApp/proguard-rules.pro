# --- JNI --------------------------------------------------------------------------------------
# Native methods are bound by name, and native code calls these callbacks by name and signature.
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keep interface io.trimio.engine.llm.local.LlamaCallback { *; }
-keepclassmembers class * implements io.trimio.engine.llm.local.LlamaCallback { boolean onText(byte[]); }
-keep interface io.trimio.engine.asr.whisper.WhisperCallback { *; }
-keepclassmembers class * implements io.trimio.engine.asr.whisper.WhisperCallback { *; }
# The image engine reports each denoising step through onStep(int, int) (1.0.20 shipped without it: R8 dropped the
# name, so a picture that takes minutes on a phone moved no progress).
-keep interface io.trimio.engine.illustration.generate.SdNative$StepListener { *; }
-keepclassmembers class * implements io.trimio.engine.illustration.generate.SdNative$StepListener { *; }

# --- kotlinx.serialization (projects, timelines, style packs, plans) --------------------------
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers class io.trimio.** { *** Companion; }
-keepclasseswithmembers class io.trimio.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class io.trimio.**$$serializer { *; }

# --- Anthropic Java SDK (Jackson maps its models reflectively) --------------------------------
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.hc.**
-dontwarn org.slf4j.**
-dontwarn kotlin.reflect.jvm.internal.**

# --- Ktor / OkHttp optional platform integrations ---------------------------------------------
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Jackson's Java 8+ reflection helpers reference JDK types Android does not ship; they are never reached.
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType

# ONNX Runtime: its native code builds Java objects (NodeInfo, OnnxTensor, …) by name and
# constructor signature through JNI. Shrinking or renaming any of them aborts the process
# (1.0.15/1.0.16: NoSuchMethodError for NodeInfo.<init> in OrtSession.getInputInfo).
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
