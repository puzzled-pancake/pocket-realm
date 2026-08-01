// jni_shim.cpp — real JNI glue for libpocketpkgtest.so. Exports the methods
// declared `external` in com.pocketrealm.pkg.PkgNative using JNI symbol naming
// (Java_<pkg>_<class>_<method>), found by the JVM without RegisterNatives.
//
// All symbols are C-linkage; no C++ exception or STL type crosses JNI. Every
// call that can fail returns a typed result so Kotlin can record evidence.
#include "pocket_pkg.h"

#include <jni.h>
#include <cstring>
#include <cstdlib>
#include <string>

namespace {
// The JNI methods are declared in com.pocketrealm.pkg.PkgNative (the Kotlin
// object). loadRealmSoBySonameNative returns a top-level RealmSoInfoParcelable
// (a Parcelable), whose class name is unambiguous in JNI (no nested $).
constexpr const char* kRealmInfoClass = "com/pocketrealm/pkg/RealmSoInfoParcelable";

// Copy a C string into a new jstring; null-safe.
jstring to_jstring(JNIEnv* env, const char* s)
{
    return env->NewStringUTF(s ? s : "");
}
}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_pocketrealm_pkg_PkgNative_helloNative(JNIEnv* env, jclass)
{
    // Marker string PKG-01/02 assert on to prove the shim answered.
    return to_jstring(env, "pocket-realm-pkg-ok");
}

JNIEXPORT jint JNICALL
Java_com_pocketrealm_pkg_PkgNative_probePageSizeNative(JNIEnv*, jclass)
{
    return (jint)pkg_probe_page_size();
}

JNIEXPORT jobject JNICALL
Java_com_pocketrealm_pkg_PkgNative_loadRealmSoBySonameNative(JNIEnv* env, jclass)
{
    pkg_realm_so_info info;
    int rc = pkg_load_realm_so_by_soname(&info);

    // Build com.pocketrealm.pkg.RealmSoInfoParcelable(loaded, err, path, soname,
    //   symbol, baseAddr). Top-level Parcelable; ctor signature is stable.
    jclass cls = env->FindClass(kRealmInfoClass);
    if (!cls) return nullptr;
    jmethodID ctor = env->GetMethodID(
        cls, "<init>",
        "(IILjava/lang/String;Ljava/lang/String;Ljava/lang/String;J)V");
    if (!ctor) return nullptr;

    jstring jpath = to_jstring(env, info.path);
    jstring jsoname = to_jstring(env, info.soname);
    jstring jsymbol = to_jstring(env, info.symbol);
    jobject obj = env->NewObject(cls, ctor,
        (jint)info.loaded, (jint)rc, jpath, jsoname, jsymbol,
        (jlong)info.base_addr);
    env->DeleteLocalRef(jpath);
    env->DeleteLocalRef(jsoname);
    env->DeleteLocalRef(jsymbol);
    return obj;
}

JNIEXPORT void JNICALL
Java_com_pocketrealm_pkg_PkgNative_crashNative(JNIEnv*, jclass, jint kind)
{
    pkg_crash((int32_t)kind);
}

}  // extern "C"
