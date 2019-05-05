#include <jni.h>
#include <string>
#include <fcntl.h>
#include <errno.h>
#include <unistd.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_a4over6_MainActivity_stringFromJNI(
        JNIEnv *env,
        jobject /* this */) {
    std::string hello = "hello C++";
    return env->NewStringUTF(hello.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_a4over6_MainActivity_getInfoFromJNI(JNIEnv *env, jobject instance, jstring s_) {
    const char *s = env->GetStringUTFChars(s_, 0);

    int fifo_handle;
    if ((fifo_handle = open(s, O_RDWR|O_CREAT)) < 0)  {
        printf("wlh open fifo error %d:%s\n", errno, strerror(errno)); 
    }

    static char fifo_buf[101];
    ssize_t size = read(fifo_handle, fifo_buf, 100);

    if (size < 0) {
        printf("wlh read fifo error");
        fifo_buf[0] = '\0';
    } 

    if (size >= 0){
        fifo_buf[size] = '\0';
    }

    close(fifo_handle);

    env->ReleaseStringUTFChars(s_, s);

    return env->NewStringUTF(fifo_buf);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_a4over6_MainActivity_runBackendThread(JNIEnv *env, jobject instance) {

    // TODO

}