#include <jni.h>
#include <string>
#include <fcntl.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <pthread.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <android/log.h>
#include <errno.h>

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
int flow_fd = -1;

bool get_tun = false;
bool do_run = false;

int tunfd = -1;
timeval last_heart, cur_heart;
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
    static char buf[20];
    memset(buf, 0, 20);
    sprintf(buf, "%d %d %d %d ", flow_recv, cnt_recv, flow_send, cnt_send);
    int size;

    size = write(flow_fd, buf, 20);
    if (size < 0) {
        printf("write to pipe error\n");
    }
}

void send_heart_packet(int sockfd) {
    heart_packet.type = 104;
    heart_packet.length = 0;
    send_msg((char *) &heart_packet, sizeof(int) + sizeof(char), sockfd);
}

void *timer_thread(void *arg) {
    int sockfd = *((int *) arg);
    while (do_run) {
        sleep(1);
        send_flow_pipe();
        flow_recv = 0;
        cnt_recv = 0;
        flow_send = 0;
        cnt_send = 0;
        gettimeofday(&cur_heart, 0);
        int elapse_t = cur_heart.tv_sec - last_heart.tv_sec;
        printf("elapse_t: %d", elapse_t);
        if (elapse_t < 60) {
            timer_cnt++;
            if (timer_cnt == 20) {
                send_heart_packet(sockfd);
                timer_cnt = 0;
            }
        } else {
            do_run = false;
            return NULL;
        }
    }
}

void send_ip_pipe(int sockfd) {
    printf("ip back length: %d", recv_pack.length);
    char buf[100];
    memset(buf, 0, 100);
    sprintf(buf, "%d %d %s", 0, sockfd, recv_pack.data);
    int size;
    printf("%s", ip_pipe_name);
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
    int flag;
    int fifo_handle;
    fifo_handle = open(ip_pipe_name, O_RDWR | O_CREAT);
    if (fifo_handle < 0) {
        printf("open fifo error %d:%s\n", errno, strerror(errno));
        close(fifo_handle);
    }

    while (do_run) {
        if (access(ip_pipe_name, F_OK) >= 0) {
            int size = read(fifo_handle, buf, 10);
            if (size < 0) {
                printf("read fifo error");
                close(fifo_handle);
                continue;
            } else if (size > 0) {
                sscanf(buf, "%d", &flag);
                if (flag == 1) {
                    sscanf(buf, "%d %d", &flag, &tunfd);
                    printf("get tun fd: %d", tunfd);
                    close(fifo_handle);
                    get_tun = true;
                    return;
                } else {
                    lseek(fifo_handle, 0, SEEK_SET);
                }
            }
        }
    }

}

void *read_tun_thread(void *arg) {
    int sockfd = *((int *) arg);
    while (do_run) {
        //printf("read data from tun: %d", tunfd);
        memset(&tun_packet, 0, sizeof(Msg));
        tun_packet.type = 102;
        tun_packet.length = read(tunfd, tun_packet.data, MAX_DATA_LEN);
        if (tun_packet.length > 0) {
            tun_packet.length += sizeof(int) + sizeof(char);
            send_msg((char *) &tun_packet, tun_packet.length, sockfd);
        }
    }
}

void write_to_tun() {
    int size_e = sizeof(int) + sizeof(char);
    ssize_t len = write(tunfd, recv_pack.data, recv_pack.length - size_e);
    if (len != recv_pack.length - size_e) {
        printf("write to pipe error");
    }
}

void recv_from_server(int sockfd) {
    pthread_t read_tun_t;
    while (do_run) {
        int len = read(sockfd, (void*)&recv_pack, sizeof(struct Msg));
        if (len < 0) {
            printf("recv error!\n");
            continue;
        }

        if (recv_pack.type == 101) {
            if (!get_tun) {
                printf("create a tun thread");
                send_ip_pipe(sockfd);
                read_fd_pipe();
                pthread_create(&read_tun_t, NULL, read_tun_thread, (void *) &sockfd);
            }
        } else if (recv_pack.type == 103) {
            printf("recv packet content: %s", recv_pack.data);
            printf("packet len:%d", recv_pack.length);
            write_to_tun();
            flow_recv += recv_pack.length;
            cnt_recv++;
        } else if (recv_pack.type == 104) {
            printf("recv heartbeat");
            gettimeofday(&last_heart, 0);
        }
    }
    pthread_join(read_tun_t, NULL);
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

    flow_pipe_name = flow_pipe;
    ip_pipe_name = ip_pipe;

    get_tun = false;
    do_run = true;
    gettimeofday(&last_heart, 0);

    mknod(flow_pipe_name, S_IFIFO | 0666, 0);//创建有名管道 
    flow_fd = open(flow_pipe_name, O_RDWR | O_CREAT | O_TRUNC);
    if (flow_fd < 0) {
        printf("open flow pipe error\n");
        return;
    }

    printf("start establish network");
    printf("msg size: %d", sizeof(Msg));
    int sockfd;

    sockfd = socket(AF_INET6, SOCK_STREAM, 0);
    if (sockfd < 0) {
        printf("create socket error!\n");
        return;
    }

    int port_t = atoi(port);

    printf("port: %d", port_t);
    struct sockaddr_in6 dest;

    bzero(&dest, sizeof(dest));
    dest.sin6_family = AF_INET6;
    dest.sin6_port = htons(port_t);

    printf("ipv6 %s", ipv6);
    int ret = inet_pton(AF_INET6, ipv6, &(dest.sin6_addr));
    if (ret <= 0) {
        printf("get ipv6 error\n");
        return;
    }

    //int on = 1;
    //setsockopt(sockfd, SOL_SOCKET, SO_REUSEPORT, &on, sizeof(on));
    //bind(sockfd, (struct sockaddr*)&client, sizeof(client));

    printf("start connect");
    ret = connect(sockfd, (struct sockaddr*)&dest, sizeof(dest));
    if (ret < 0) {
        printf("connect error%d:%s\n", errno, strerror(errno));
        return;
    }

    printf("finish establish ipv6 connect\n");

    //start timer
    pthread_t timer_t;
    pthread_create(&timer_t, NULL, timer_thread, (void*)&sockfd);

    send_request(sockfd);

    recv_from_server(sockfd);

    pthread_join(timer_t, NULL);

    close(flow_fd);

    env->ReleaseStringUTFChars(ipv6_, ipv6);
    env->ReleaseStringUTFChars(port_, port);
    env->ReleaseStringUTFChars(ip_pipe_, ip_pipe);
    env->ReleaseStringUTFChars(flow_pipe_, flow_pipe);

    env->ReleaseStringUTFChars(ipv6_, ipv6);
    env->ReleaseStringUTFChars(port_, port);
    env->ReleaseStringUTFChars(ip_pipe_, ip_pipe);
    env->ReleaseStringUTFChars(flow_pipe_, flow_pipe);
}