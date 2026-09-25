// JVM/Android only: hands the grammar pointer to Kotlin as a jlong, which is what
// ktreesitter's JVM `Language(Any)` constructor expects.
#include <stdint.h>
#include <jni.h>
#include "editor_grammars.h"

JNIEXPORT jlong JNICALL
Java_dev_supermux_editor_spike_Grammars_json(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)tree_sitter_json();
}
