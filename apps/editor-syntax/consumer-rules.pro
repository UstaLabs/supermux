# JNI (native/src/syntax_jni.c): the reader calls TextSourceJni.chunk(int) by name, and the
# natives bind to Ses's external methods by name.
-keepclassmembers class dev.supermux.editor.syntax.Ses$TextSourceJni { java.lang.String chunk(int); }
-keep class dev.supermux.editor.syntax.Ses { native <methods>; }
