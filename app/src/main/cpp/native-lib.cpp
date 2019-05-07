#include <jni.h>
#include <string>
#include <fcntl.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <pthread.h>
#include <sstream>
#include <sys/types.h>
#include <sys/stat.h>
#include <android/log.h>

#define MAX_DATA_LEN 4096
#define printf(...) __android_log_print(ANDROID_LOG_DEBUG, "backend", __VA_ARGS__);

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
    if ((fifo_handle = open(s, O_RDWR|O_CREAT)) < 0) {
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

extern "C" {

struct Msg {
    int length;
    char type;
    char data[MAX_DATA_LEN];
} request_pack, recv_pack, tun_packet, heart_packet;

int flow_send = 0;
int cnt_send = 0;
int flow_recv = 0;
int cnt_recv = 0;

int tunfd = -1;
timeval last_heart, cur_heart;
int heart_cnt = 0;
int timer_cnt;
const char *flow_pipe_name;
const char *ip_pipe_name;

void send_msg(char *msg, int length, int sockfd) {
    int len = send(sockfd, msg, (size_t) length, 0);
    if (len < length) {
        //printf("send error!\n");
        return;
    }
}

void send_request(int sockfd) {
    request_pack.type = 100;
    request_pack.length = 0;
    int whole_len = sizeof(int) + sizeof(char);

    send_msg((char *) &request_pack, whole_len, sockfd);
}

void send_flow_pipe() {
    //printf("write flow infomation");
    char buf[20];
    memset(buf, 0, 20);
    sprintf(buf, "%d %d %d %d ", flow_recv, cnt_recv, flow_send, cnt_send);
    int size;
    mknod(flow_pipe_name, S_IFIFO | 0666, 0);//创建有名管道 
    int fifo_handle = open(flow_pipe_name, O_RDWR | O_CREAT | O_TRUNC);
    if (fifo_handle < 0) {
        printf("open flow pipe error\n");
        return;
    }

    size = write(fifo_handle, buf, 20);
    if (size < 0) {
        printf("write to pipe error\n");
    }
    close(fifo_handle);
}

void send_heart_packet(int sockfd) {
    heart_packet.type = 104;
    heart_packet.length = 0;
    send_msg((char *) &heart_packet, sizeof(int) + sizeof(char), sockfd);
}

void *timer_thread(void *arg) {
    int sockfd = *((int *) arg);
    while (true) {
        sleep(1);
        send_flow_pipe();
        flow_recv = 0;
        cnt_recv = 0;
        flow_send = 0;
        cnt_send = 0;
        gettimeofday(&cur_heart, 0);
        int elapse_t = cur_heart.tv_sec - last_heart.tv_sec;
        if (elapse_t < 60) {
            timer_cnt++;
            if (timer_cnt == 20) {
                send_heart_packet(sockfd);
                timer_cnt = 0;
            }
        } else {
            //close socket
        }
    }
}

void send_ip_pipe(int sockfd) {
    printf("ip back length: %d", recv_pack.length);
    char buf[100];
    memset(buf, 0, 100);
    sprintf(buf, "%d %s", sockfd, recv_pack.data);
    int size;
    mknod(ip_pipe_name, S_IFIFO | 0666, 0);//创建有名管道 
    int fifo_handle = open(ip_pipe_name, O_RDWR | O_CREAT | O_TRUNC);
    if (fifo_handle < 0) {
        printf("open ip pipe error\n");
        return;
    }
    size = write(fifo_handle, buf, recv_pack.length + 5);
    if (size < 0) {
        printf("write to pipe error\n");
    }
    close(fifo_handle);
}

void read_fd_pipe() {
    char buf[10];
    int fifo_handle;
    if ((fifo_handle = open(ip_pipe_name, O_RDWR | O_CREAT)) < 0) {
        printf("open fifo error %d:%s\n", errno, strerror(errno));
        return;
    }
    int size = read(fifo_handle, buf, 10);
    if (size < 0) {
        printf("read fifo error");
    } else if (size > 0) {
        tunfd = atoi(buf);
    }
    printf("get tun fd: %d", tunfd);
    close(fifo_handle);
}

void *read_tun_thread(void *arg) {
    int sockfd = *((int *) arg);
    tun_packet.type = 102;
    while (true) {
        tun_packet.length = read(tunfd, tun_packet.data, MAX_DATA_LEN);
        if (tun_packet.length > 0) {
            printf("send packet");
            send_msg((char *) &tun_packet, tun_packet.length + sizeof(int) + sizeof(char), sockfd);
        }
    }
}

void write_to_tun() {
    ssize_t len = write(tunfd, recv_pack.data, recv_pack.length);
    if (len < recv_pack.length) {
        printf("write to pipe error");
    }
}

void recv_from_server(int sockfd) {
    while (true) {
        int len = recv(sockfd, (void*)&recv_pack, sizeof(struct Msg), 0);
        if (len < 0) {
            printf("recv error!\n");
            exit(-1);
        }

        if (recv_pack.type == 101) {
            send_ip_pipe(sockfd);
            read_fd_pipe();
            pthread_t read_tun_t;
            pthread_create(&read_tun_t, NULL, read_tun_thread, (void *) &sockfd);
        } else if (recv_pack.type == 103) {
            printf("recv packet");
            write_to_tun();
            flow_recv += recv_pack.length;
            cnt_recv++;
        } else if (recv_pack.type == 104) {
            printf("recv heartbeat");
            gettimeofday(&last_heart, 0);
        }
    }
}

}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_a4over6_MainActivity_runBackendThread(JNIEnv *env, jobject instance, jstring ipv6_,
                                                       jstring port_, jstring ip_pipe_,
                                                       jstring flow_pipe_) {
    const char *ipv6 = env->GetStringUTFChars(ipv6_, 0);
    const char *port = env->GetStringUTFChars(port_, 0);
    const char *ip_pipe = env->GetStringUTFChars(ip_pipe_, 0);
    const char *flow_pipe = env->GetStringUTFChars(flow_pipe_, 0);

    printf("start establish network");
    int sockfd;
    flow_pipe_name = flow_pipe;
    ip_pipe_name = ip_pipe;

    sockfd = socket(AF_INET6, SOCK_STREAM, 0);
    if (sockfd < 0) {
        printf("create socket error!\n");
        return;
    }

    int port_t = atoi(port);

    struct sockaddr_in6 dest;

    bzero(&dest, sizeof(dest));
    dest.sin6_family = AF_INET6;
    dest.sin6_port = htons(port_t);

    int ret = inet_pton(AF_INET6, ipv6, &(dest.sin6_addr));
    if (ret <= 0) {
        printf("get ipv6 error\n");
        return;
    }

    ret = connect(sockfd, (struct sockaddr*)&dest, sizeof(dest));
    if (ret < 0) {
        printf("connect error\n");
        return;
    }

    printf("finish establish ipv6 connect\n");

    //start timer
    pthread_t timer_t;
    pthread_create(&timer_t, NULL, timer_thread, (void*)&sockfd);

    send_request(sockfd);

    recv_from_server(sockfd);

    env->ReleaseStringUTFChars(ipv6_, ipv6);
    env->ReleaseStringUTFChars(port_, port);
    env->ReleaseStringUTFChars(ip_pipe_, ip_pipe);
    env->ReleaseStringUTFChars(flow_pipe_, flow_pipe);

    env->ReleaseStringUTFChars(ipv6_, ipv6);
    env->ReleaseStringUTFChars(port_, port);
    env->ReleaseStringUTFChars(ip_pipe_, ip_pipe);
    env->ReleaseStringUTFChars(flow_pipe_, flow_pipe);
}