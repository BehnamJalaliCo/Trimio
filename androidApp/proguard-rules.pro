# --- JNI --------------------------------------------------------------------------------------
# Native methods are bound by name, and native code calls these callbacks by name and signature.
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keep interface io.trimio.engine.llm.local.LlamaCallback { *; }
-keepclassmembers class * implements io.trimio.engine.llm.local.LlamaCallback { boolean onText(byte[]); }
-keep interface io.trimio.engine.asr.whisper.WhisperCallback { *; }
-keepclassmembers class * implements io.trimio.engine.asr.whisper.WhisperCallback { *; }

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
