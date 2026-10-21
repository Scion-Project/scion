/*
 * JNI shim for the in-process QEMU variant (QemuVmService, ":vm" process).
 *
 * Loads libqemu-system-aarch64-shared.so (Inferno built with
 * -Dsystem_shared_lib=true) and calls its qemu_android_main(). QEMU ends in
 * exit(), so run() never returns: the :vm process exits with QEMU's status,
 * which the app reads back from ActivityManager's exit info.
 *
 * With -display android (Inferno's ui/android.c) it also passes the app's
 * Surface, touches and keys to QEMU, and reports framebuffer size changes
 * back to Java (QemuNative.onResize).
 *
 * Built by native/build-android.sh into jniLibs as libqemu_jni.so.
 */
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static JavaVM *vm;
static jclass native_class;
static jmethodID on_resize;

static int (*qemu_main)(int, char **);
/* From ui/android.c; NULL when the library was built without -display android. */
static void (*set_window)(ANativeWindow *);
static void (*set_resize_cb)(void (*)(int, int));
static void (*input_touch)(int, int, int, int, int);
static int (*input_key)(const char *, int);

JNIEXPORT jint JNI_OnLoad(JavaVM *jvm, void *reserved)
{
    (void)reserved;
    vm = jvm;
    return JNI_VERSION_1_6;
}

/* Called on QEMU's main loop thread, which the JVM doesn't know yet. */
static void resize_cb(int w, int h)
{
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK &&
        (*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) {
        return;
    }
    (*env)->CallStaticVoidMethod(env, native_class, on_resize, w, h);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

/* Loads QEMU; returns NULL, or an error message. */
JNIEXPORT jstring JNICALL
Java_io_github_dylanneve1_iosonpixel_QemuNative_load(JNIEnv *env, jclass cls, jstring jlib)
{
    const char *s = (*env)->GetStringUTFChars(env, jlib, NULL);
    void *lib = dlopen(s, RTLD_NOW | RTLD_LOCAL);
    (*env)->ReleaseStringUTFChars(env, jlib, s);
    if (lib) qemu_main = (int (*)(int, char **))dlsym(lib, "qemu_android_main");
    if (!qemu_main) return (*env)->NewStringUTF(env, dlerror());

    set_window = (void (*)(ANativeWindow *))dlsym(lib, "qemu_android_display_set_window");
    set_resize_cb = (void (*)(void (*)(int, int)))dlsym(lib, "qemu_android_display_set_resize_cb");
    input_touch = (void (*)(int, int, int, int, int))dlsym(lib, "qemu_android_input_touch");
    input_key = (int (*)(const char *, int))dlsym(lib, "qemu_android_input_key");
    if (set_resize_cb) {
        native_class = (*env)->NewGlobalRef(env, cls);
        on_resize = (*env)->GetStaticMethodID(env, cls, "onResize", "(II)V");
        set_resize_cb(resize_cb);
    }
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_io_github_dylanneve1_iosonpixel_QemuNative_hasDisplay(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return set_window != NULL;
}

JNIEXPORT void JNICALL
Java_io_github_dylanneve1_iosonpixel_QemuNative_run(JNIEnv *env, jclass cls, jobjectArray jargv, jstring jcwd)
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
    exit(qemu_main(argc, argv));
}

/* Draw into [surface] from now on, or stop drawing if it is null. */
JNIEXPORT void JNICALL
Java_io_github_dylanneve1_iosonpixel_QemuNative_setSurface(JNIEnv *env, jclass cls, jobject surface)
{
    (void)cls;
    if (!set_window) return;
    ANativeWindow *win = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    set_window(win); /* takes its own reference */
    if (win) ANativeWindow_release(win);
}

JNIEXPORT void JNICALL
Java_io_github_dylanneve1_iosonpixel_QemuNative_touch(JNIEnv *env, jclass cls, jint x, jint y, jint w, jint h,
                                                       jboolean down)
{
    (void)env; (void)cls;
    if (input_touch) input_touch(x, y, w, h, down);
}

JNIEXPORT void JNICALL
Java_io_github_dylanneve1_iosonpixel_QemuNative_key(JNIEnv *env, jclass cls, jstring jname, jboolean down)
{
    (void)cls;
    if (!input_key) return;
    const char *s = (*env)->GetStringUTFChars(env, jname, NULL);
    input_key(s, down);
    (*env)->ReleaseStringUTFChars(env, jname, s);
}
