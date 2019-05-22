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
#include <netinet/tcp.h>
#include <netinet/ip.h>
#include <netdb.h>

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
int whole_send = 0;
int flow_recv = 0;
int whole_recv = 0;
int flow_fd = -1;

bool get_tun = false;
bool do_run = false;

int tunfd = -1;
timeval last_heart, cur_heart, init_time;
int timer_cnt;
const char *flow_pipe_name;
const char *ip_pipe_name;
pthread_mutex_t mutex_info, mutex_run;
int sockfd;

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

bool get_dorun() {
    pthread_mutex_lock(&mutex_run);
    bool ret = do_run;
    pthread_mutex_unlock(&mutex_run);
    return ret;
}

void send_flow_pipe() {
    //printf("write flow infomation");
    char buf[50];
    memset(buf, 0, 50);

    int elapse = cur_heart.tv_sec - init_time.tv_sec;
    int bs = sprintf(buf, "%d %d %d %d %d ", flow_recv, whole_recv, flow_send, whole_send, elapse);
    int size;

    lseek(flow_fd, 0, SEEK_SET);
    size = write(flow_fd, buf, bs);
    if (size < 0) {
        printf("write to pipe error\n");
    }
    printf("send flow info: %s", buf);
}

void send_heart_packet(int sockfd) {
    heart_packet.type = 104;
    heart_packet.length = 0;
    send_msg((char *) &heart_packet, sizeof(int) + sizeof(char), sockfd);
}

void *timer_thread(void *arg) {
    int sockfd = *((int *) arg);
    while (get_dorun()) {
        sleep(1);
        gettimeofday(&cur_heart, 0);
        int elapse_t = cur_heart.tv_sec - last_heart.tv_sec;
        send_flow_pipe();

        pthread_mutex_lock(&mutex_info);
        flow_recv = 0;
        flow_send = 0;
        pthread_mutex_unlock(&mutex_info);
        //printf("elapse_t: %d", elapse_t);
        if (elapse_t < 60) {
            timer_cnt++;
            if (timer_cnt == 20) {
                send_heart_packet(sockfd);
                timer_cnt = 0;
            }
        } else {
            do_run = false;
            shutdown(sockfd, SHUT_RDWR);
            close(sockfd);
            return NULL;
        }
    }
    printf("timer thread leave");
    return NULL;
}

void send_ip_pipe(int sockfd) {
    printf("ip back length: %d", recv_pack.length);
    char buf[100];
    memset(buf, 0, 100);
    sprintf(buf, "%d %d %s", 0, sockfd, recv_pack.data);
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

void *read_tun_thread(void *arg) {
    while (!get_tun) ;
    int sockfd = *((int *) arg);
    while (get_dorun()) {
        memset(&tun_packet, 0, sizeof(Msg));
        tun_packet.type = 102;
        tun_packet.length = read(tunfd, tun_packet.data, MAX_DATA_LEN);
        if (tun_packet.length > 0) {
            tun_packet.length += sizeof(int) + sizeof(char);
            send_msg((char *) &tun_packet, tun_packet.length, sockfd);

            pthread_mutex_lock(&mutex_info);
            whole_send += tun_packet.length;
            flow_send += tun_packet.length;
            pthread_mutex_unlock(&mutex_info);

            //struct ip *head = (struct ip*)tun_packet.data;
            //printf("from %s to %s", inet_ntoa(head->ip_src), inet_ntoa(head->ip_dst));
        }
    }
    printf("read from tun leave");
    return NULL;
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
    int header_size = sizeof(int) + sizeof(char);
    while (get_dorun()) {
        int len = 0, recv_n;
        while (get_dorun() && (len < header_size)) {
            recv_n = read(sockfd, (char*)&recv_pack + len, header_size);
            if (recv_n == -1) {
                usleep(100);
                continue;
            } else if (recv_n == 0) {

            } else if (recv_n > 0) {
                len += recv_n;
            } else {
                printf("socket recv error");
            }
        }

        printf("recv packet content: %s", recv_pack.data);
        printf("packet len:%d", recv_pack.length);
        len = recv_pack.length - 5;

        if (len < 0) {
            printf("recv error!\n");
            continue;
        }

        int len_i = 0;
        while (get_dorun() && (len_i < len)) {
            recv_n = recv(sockfd, recv_pack.data + len_i, 1, 0);
            if (recv_n == -1) {
                usleep(100);
                continue;
            } else if (recv_n == 0) {

            } else if (recv_n > 0) {
                len_i += recv_n;
            } else {
                printf("socket recv error");
            }
        }

        if (recv_pack.type == 101) {
            if (!get_tun) {
                printf("send back ip info");
                send_ip_pipe(sockfd);
                pthread_create(&read_tun_t, NULL, read_tun_thread, (void *) &sockfd);
            }
        } else if (recv_pack.type == 103) {
            write_to_tun();
            pthread_mutex_lock(&mutex_info);
            flow_recv += recv_pack.length;
            whole_recv += recv_pack.length;
            pthread_mutex_unlock(&mutex_info);
        } else if (recv_pack.type == 104) {
            printf("recv heartbeat");
            gettimeofday(&last_heart, 0);
        }
    }
    printf("recv from server leave");
    if (get_tun) {
        pthread_join(read_tun_t, NULL);
    }
}

void send_stop_info(const char* msg) {
    char buf[100];
    int size = 0;
    size = sprintf(buf, "%d %s ", 2, msg);
    buf[size] = '\0';
    size++;
    mknod(ip_pipe_name, S_IFIFO | 0666, 0);//创建有名管道 
    int fifo_handle = open(ip_pipe_name, O_RDWR | O_CREAT | O_TRUNC);
    if (fifo_handle < 0) {
        printf("open ip pipe error\n");
        return;
    }
    size = write(fifo_handle, buf, size);
    if (size < 0) {
        printf("write to pipe error\n");
    }
    close(fifo_handle);
}

void *read_ip_pipe(void *arg) {
    char buf[100];
    bool isOpen = false;
    int flag;
    int fifo_handle;


    while (get_dorun()) {
        if (!isOpen) {
            fifo_handle = open(ip_pipe_name, O_RDWR | O_CREAT);
            if (fifo_handle < 0) {
                printf("open fifo error %d:%s\n", errno, strerror(errno));
                close(fifo_handle);
                continue;
            } else {
                isOpen = true;
            }
        }
        int size = read(fifo_handle, buf, 100);
        if (size < 0) {
            printf("read ip pipe error");
            close(fifo_handle);
            isOpen = false;
            continue;
        } else if (size > 0) {
            sscanf(buf, "%d", &flag);
            if (flag == 1) {
                if (!get_tun) {
                    sscanf(buf, "%d %d", &flag, &tunfd);
                    printf("get tun fd: %d", tunfd);
                    close(fifo_handle);
                    get_tun = true;
                }
            } else if (flag == 3) {
                printf("start to unlink");
                do_run = false;
            } else {
                lseek(fifo_handle, 0, SEEK_SET);
            }
        }
    }
    printf("read ip leave");
    return NULL;
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

    //initialize
    flow_pipe_name = flow_pipe;
    ip_pipe_name = ip_pipe;

    get_tun = false;
    gettimeofday(&last_heart, 0);
    gettimeofday(&init_time, 0);
    pthread_mutex_init(&mutex_info, NULL);
    pthread_mutex_init(&mutex_run, NULL);
    do_run = true;
    whole_recv = 0;
    whole_send = 0;

    mknod(flow_pipe_name, S_IFIFO | 0666, 0);//创建有名管道 
    flow_fd = open(flow_pipe_name, O_RDWR | O_CREAT | O_TRUNC);
    if (flow_fd < 0) {
        send_stop_info("open flow pipe error\n");

        env->ReleaseStringUTFChars(ipv6_, ipv6);
        env->ReleaseStringUTFChars(port_, port);
        env->ReleaseStringUTFChars(ip_pipe_, ip_pipe);
        env->ReleaseStringUTFChars(flow_pipe_, flow_pipe);
        pthread_mutex_destroy(&mutex_info);
        pthread_mutex_destroy(&mutex_run);
        return;
    }

    printf("start establish network");
    printf("msg size: %d", sizeof(Msg));


    int n;
    struct addrinfo hints, *res, *ressave;
    bzero(&hints, sizeof (struct addrinfo));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    if ( (n = getaddrinfo (ipv6, port, &hints, &res)) != 0) {
        send_stop_info("get addrInfo error\n");

        env->ReleaseStringUTFChars(ipv6_, ipv6);
        env->ReleaseStringUTFChars(port_, port);
        env->ReleaseStringUTFChars(ip_pipe_, ip_pipe);
        env->ReleaseStringUTFChars(flow_pipe_, flow_pipe);
        pthread_mutex_destroy(&mutex_info);
        pthread_mutex_destroy(&mutex_run);
        if (res != NULL) freeaddrinfo(ressave);
        return;
    }
    ressave = res;
    do {
        sockfd = socket (res->ai_family, res->ai_socktype, res->ai_protocol);
        if (sockfd < 0) {
            printf("v6fd<0");
            continue;
        }
        int enable=1;
        setsockopt(sockfd,IPPROTO_TCP,TCP_NODELAY,&enable,sizeof(enable));
        if (sockfd < 0) {
            printf("close tcp nodelay error");
            continue;
        }

        /*ignore this one */
        if (connect(sockfd, res->ai_addr, res->ai_addrlen) == 0) {
            /* success */
            break;
        } else{
            printf("connect failed");
        }
        close(sockfd);
        sockfd=-1;
        /* ignore this one */
    } while ( (res = res->ai_next) != NULL);
    if (res == NULL) {
        /* errno set from final connect2Server() */
        printf("res == NULL\n");
        send_stop_info("res == NULL\n");

        env->ReleaseStringUTFChars(ipv6_, ipv6);
        env->ReleaseStringUTFChars(port_, port);
        env->ReleaseStringUTFChars(ip_pipe_, ip_pipe);
        env->ReleaseStringUTFChars(flow_pipe_, flow_pipe);
        pthread_mutex_destroy(&mutex_info);
        pthread_mutex_destroy(&mutex_run);
        freeaddrinfo(ressave);
        return ;
    }
    freeaddrinfo(ressave);


//
//    int port_t = atoi(port);
//
//    printf("port: %d", port_t);
//    struct sockaddr_in6 dest;
//
//    bzero(&dest, sizeof(dest));
//    dest.sin6_family = AF_INET6;
//    dest.sin6_port = htons(port_t);
//
//    printf("ipv6 %s", ipv6);
//    int ret = inet_pton(AF_INET6, ipv6, &(dest.sin6_addr));
//    if (ret <= 0) {
//        send_stop_info("get ipv6 error\n");
//
//        env->ReleaseStringUTFChars(ipv6_, ipv6);
//        env->ReleaseStringUTFChars(port_, port);
//        env->ReleaseStringUTFChars(ip_pipe_, ip_pipe);
//        env->ReleaseStringUTFChars(flow_pipe_, flow_pipe);
//        pthread_mutex_destroy(&mutex_info);
//        pthread_mutex_destroy(&mutex_run);
//        return;
//    }
//    printf("start connect");
//    ret = connect(sockfd, (struct sockaddr*)&dest, sizeof(dest));
//    if (ret < 0) {
//        printf("connect error %d:%s\n", errno, strerror(errno));
//        send_stop_info("connect error\n");
//
//        env->ReleaseStringUTFChars(ipv6_, ipv6);
//        env->ReleaseStringUTFChars(port_, port);
//        env->ReleaseStringUTFChars(ip_pipe_, ip_pipe);
//        env->ReleaseStringUTFChars(flow_pipe_, flow_pipe);
//        pthread_mutex_destroy(&mutex_info);
//        pthread_mutex_destroy(&mutex_run);
//        return;
//    }

    printf("finish establish ipv6 connect\n");

    //start timer
    pthread_t timer_t, read_ip_t;
    pthread_create(&timer_t, NULL, timer_thread, (void*)&sockfd);
    pthread_create(&read_ip_t, NULL, read_ip_pipe, NULL);

    send_request(sockfd);

    recv_from_server(sockfd);

    pthread_join(timer_t, NULL);
    pthread_join(read_ip_t, NULL);
    pthread_mutex_destroy(&mutex_info);
    pthread_mutex_destroy(&mutex_run);

    close(flow_fd);
    close(sockfd);
    send_stop_info("unlink");
    printf("backend leave");

    env->ReleaseStringUTFChars(ipv6_, ipv6);
    env->ReleaseStringUTFChars(port_, port);
    env->ReleaseStringUTFChars(ip_pipe_, ip_pipe);
    env->ReleaseStringUTFChars(flow_pipe_, flow_pipe);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_a4over6_MainActivity_backend_1unlink(JNIEnv *env, jobject instance) {
    pthread_mutex_lock(&mutex_run);
    do_run = false;
    pthread_mutex_unlock(&mutex_run);
    printf("get unlink sign");
    shutdown(sockfd, SHUT_RDWR);
    close(sockfd);
}