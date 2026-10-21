/*
 * JNI shim for the in-process QEMU variant (QemuVmService, ":vm" process).
 *
 * Loads libqemu-system-aarch64-shared.so (Inferno built with
 * -Dsystem_shared_lib=true) and calls its qemu_android_main(). QEMU ends in
 * exit(), so this never returns: the :vm process exits with QEMU's status,
 * which the app reads back from ActivityManager's exit info.
 *
 * Built by native/build-android.sh into jniLibs as libqemu_jni.so.
 */
#include <dlfcn.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

JNIEXPORT void JNICALL
Java_io_github_dylanneve1_iosonpixel_QemuNative_run(JNIEnv *env, jclass cls, jstring jlib,
                                                     jobjectArray jargv, jstring jcwd)
{
    (void)cls;
    const char *s = (*env)->GetStringUTFChars(env, jcwd, NULL);
    /* The exec path runs QEMU with the VM folder as its working directory. */
    if (chdir(s) != 0) {
        fprintf(stderr, "error: cannot enter %s\n", s);
        exit(127);
    }
    (*env)->ReleaseStringUTFChars(env, jcwd, s);

    jsize argc = (*env)->GetArrayLength(env, jargv);
    char **argv = calloc((size_t)argc + 1, sizeof(char *));
    for (jsize i = 0; i < argc; i++) {
        jstring a = (jstring)(*env)->GetObjectArrayElement(env, jargv, i);
        s = (*env)->GetStringUTFChars(env, a, NULL);
        argv[i] = strdup(s);
        (*env)->ReleaseStringUTFChars(env, a, s);
        (*env)->DeleteLocalRef(env, a);
    }

    s = (*env)->GetStringUTFChars(env, jlib, NULL);
    void *lib = dlopen(s, RTLD_NOW | RTLD_LOCAL);
    int (*qemu_main)(int, char **) = lib ? (int (*)(int, char **))dlsym(lib, "qemu_android_main") : NULL;
    if (!qemu_main) {
        fprintf(stderr, "error: cannot load QEMU from %s: %s\n", s, dlerror());
        exit(127);
    }
    (*env)->ReleaseStringUTFChars(env, jlib, s);

    exit(qemu_main(argc, argv));
}
