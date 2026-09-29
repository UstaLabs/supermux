# JNI (native/src/syntax_jni.c): the reader calls TextSourceJni.chunk(int) and the regex matcher
# MatcherJni.match(int, String) by name, and the natives bind to Ses's external methods by name.
-keepclassmembers class dev.supermux.editor.syntax.Ses$TextSourceJni { java.lang.String chunk(int); }
-keepclassmembers class dev.supermux.editor.syntax.Ses$MatcherJni { boolean match(int, java.lang.String); }
-keep class dev.supermux.editor.syntax.Ses { native <methods>; }
