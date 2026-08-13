#include <stdbool.h>
#include <stdint.h>
#include <jni.h>
#include <sys/epoll.h>
#include <sys/poll.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/eventfd.h>
#include <sys/un.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <malloc.h>
#include <pthread.h>

#include "winlator.h"
#include "jni_utils.h"

#define MAX_EVENTS 10

typedef struct JMethods {
    JavaVM* jvm;
    JNIEnv* env;
    jobject obj;
    jmethodID handleConnectionShutdown;
    jmethodID handleNewConnection;
    jmethodID handleExistingConnection;
    jmethodID killAllConnections;
    jmethodID handleNativeDestroyComplete;
} JMethods;

typedef struct ConnectedClient ConnectedClient;

typedef struct XConnectorEpoll {
    pthread_t epollThread;
    bool epollThreadStarted;
    bool destroyOnListenerExit;
    bool running;
    int epollFd;
    int serverFd;
    int shutdownFd;
    bool multithreadedClients;
    pthread_mutex_t clientsMutex;
    pthread_cond_t clientsChanged;
    bool clientsMutexInitialized;
    bool clientsChangedInitialized;
    ConnectedClient* clients;
    JMethods jmethods;
} XConnectorEpoll;

typedef enum ClientCleanupOwner {
    CLIENT_CLEANUP_NONE = 0,
    CLIENT_CLEANUP_REAPER,
    CLIENT_CLEANUP_EXTERNAL,
} ClientCleanupOwner;

struct ConnectedClient {
    pthread_t pollThread;
    bool pollThreadStarted;
    int fd;
    int shutdownFd;
    bool running;
    bool exited;
    ClientCleanupOwner cleanupOwner;
    void* tag;
    JMethods jmethods;
    XConnectorEpoll* connector;
    ConnectedClient* next;
};

static void closeOwnedFd(int* fd) {
    int ownedFd = *fd;
    *fd = -1;
    if (ownedFd >= 0) close(ownedFd);
}

static int createServerSocket(const char* sockPath) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;

    struct sockaddr_un serverAddr = {0};
    serverAddr.sun_family = AF_LOCAL;

    int addrLength = sizeof(sa_family_t) + strlen(sockPath);
    strncpy(serverAddr.sun_path, sockPath, sizeof(serverAddr.sun_path) - 1);

    unlink(serverAddr.sun_path);
    if (bind(fd, (struct sockaddr*) &serverAddr, addrLength) < 0) goto error;
    if (listen(fd, MAX_EVENTS) < 0) goto error;

    return fd;

error:
    closeOwnedFd(&fd);
    return -1;
}

static void loadJMethods(JMethods* jmethods) {
    JNIEnv* env;
    (*jmethods->jvm)->AttachCurrentThread(jmethods->jvm, &env, NULL);
    jmethods->env = env;

    jclass cls = (*env)->GetObjectClass(env, jmethods->obj);
    jmethods->handleConnectionShutdown = (*env)->GetMethodID(env, cls, "handleConnectionShutdown", "(Ljava/lang/Object;)V");
    jmethods->handleNewConnection = (*env)->GetMethodID(env, cls, "handleNewConnection", "(JI)Ljava/lang/Object;");
    jmethods->handleExistingConnection = (*env)->GetMethodID(env, cls, "handleExistingConnection", "(Ljava/lang/Object;)V");
    jmethods->killAllConnections = (*env)->GetMethodID(env, cls, "killAllConnections", "()V");
    jmethods->handleNativeDestroyComplete = (*env)->GetMethodID(env, cls, "handleNativeDestroyComplete", "()V");
}

static bool addFdToEpoll(int epollFd, int fd, void* ptr) {
    struct epoll_event event = {0};
    if (ptr) event.data.ptr = ptr;
    else event.data.fd = fd;
    event.events = EPOLLIN;
    return epoll_ctl(epollFd, EPOLL_CTL_ADD, fd, &event) == 0;
}

static void removeFdFromEpoll(int epollFd, int fd) {
    if (epollFd >= 0 && fd >= 0) epoll_ctl(epollFd, EPOLL_CTL_DEL, fd, NULL);
}

static int waitForSocketRead(jint clientFd, jint shutdownFd) {
    struct pollfd pfds[2] = {0};
    pfds[0].fd = clientFd;
    pfds[0].events = POLLIN;

    pfds[1].fd = shutdownFd;
    pfds[1].events = POLLIN;

    int res;
    do {
        res = poll(pfds, 2, -1);
    } while (res < 0 && errno == EINTR);

    if (res < 0 || (pfds[1].revents & (POLLIN | POLLERR | POLLHUP | POLLNVAL))) return -1;
    /* A peer may send its final payload and close before poll wakes. Preserve
     * that readable payload before treating the simultaneous HUP as terminal. */
    if (pfds[0].revents & POLLIN) return 1;
    if (pfds[0].revents & (POLLERR | POLLHUP | POLLNVAL)) return -1;
    return 0;
}

static void requestShutdown(int fd) {
    if (fd < 0) return;
    const uint64_t shutdownValue = 1;
    ssize_t ignored = write(fd, &shutdownValue, sizeof(shutdownValue));
    (void)ignored;
}

static void drainShutdownFd(int fd) {
    uint64_t value;
    while (read(fd, &value, sizeof(value)) == sizeof(value)) {}
}

static bool connectorIsRunning(XConnectorEpoll* connector) {
    pthread_mutex_lock(&connector->clientsMutex);
    bool running = connector->running;
    pthread_mutex_unlock(&connector->clientsMutex);
    return running;
}

static bool clientIsRunning(ConnectedClient* client) {
    XConnectorEpoll* connector = client->connector;
    pthread_mutex_lock(&connector->clientsMutex);
    bool running = client->running;
    pthread_mutex_unlock(&connector->clientsMutex);
    return running;
}

static ConnectedClient* findClientLocked(XConnectorEpoll* connector, ConnectedClient* requested) {
    for (ConnectedClient* client = connector->clients; client; client = client->next) {
        if (client == requested) return client;
    }
    return NULL;
}

static void addClientLocked(XConnectorEpoll* connector, ConnectedClient* client) {
    client->next = connector->clients;
    connector->clients = client;
}

static bool removeClientLocked(XConnectorEpoll* connector, ConnectedClient* client) {
    ConnectedClient** link = &connector->clients;
    while (*link) {
        if (*link == client) {
            *link = client->next;
            client->next = NULL;
            pthread_cond_broadcast(&connector->clientsChanged);
            return true;
        }
        link = &(*link)->next;
    }
    return false;
}

static void notifyConnectionShutdown(ConnectedClient* client, JMethods* jmethods) {
    if (!client->tag) return;
    (*jmethods->env)->CallVoidMethod(jmethods->env, jmethods->obj,
                                    jmethods->handleConnectionShutdown, client->tag);
    client->tag = NULL;
}

/* Caller owns cleanup and, for a threaded client, has already joined it. */
static void releaseClient(ConnectedClient* client) {
    XConnectorEpoll* connector = client->connector;

    pthread_mutex_lock(&connector->clientsMutex);
    bool removed = removeClientLocked(connector, client);
    pthread_mutex_unlock(&connector->clientsMutex);
    if (!removed) return;

    if (!connector->multithreadedClients) removeFdFromEpoll(connector->epollFd, client->fd);
    closeOwnedFd(&client->shutdownFd);
    closeOwnedFd(&client->fd);
    free(client);
}

static void stopAndReleaseThreadedClient(ConnectedClient* client) {
    requestShutdown(client->shutdownFd);
    if (client->pollThreadStarted && !pthread_equal(pthread_self(), client->pollThread)) {
        pthread_join(client->pollThread, NULL);
    }
    releaseClient(client);
}

static void reapExitedClients(XConnectorEpoll* connector) {
    while (true) {
        ConnectedClient* reap = NULL;

        pthread_mutex_lock(&connector->clientsMutex);
        for (ConnectedClient* client = connector->clients; client; client = client->next) {
            if (client->exited && client->cleanupOwner == CLIENT_CLEANUP_REAPER) {
                client->cleanupOwner = CLIENT_CLEANUP_EXTERNAL;
                reap = client;
                break;
            }
        }
        pthread_mutex_unlock(&connector->clientsMutex);

        if (!reap) return;
        stopAndReleaseThreadedClient(reap);
    }
}

static void drainClients(XConnectorEpoll* connector) {
    while (true) {
        ConnectedClient* client = NULL;

        pthread_mutex_lock(&connector->clientsMutex);
        while (connector->clients &&
               connector->clients->cleanupOwner == CLIENT_CLEANUP_EXTERNAL) {
            pthread_cond_wait(&connector->clientsChanged, &connector->clientsMutex);
        }
        client = connector->clients;
        if (client) {
            client->cleanupOwner = CLIENT_CLEANUP_EXTERNAL;
            client->running = false;
        }
        pthread_mutex_unlock(&connector->clientsMutex);

        if (!client) return;
        if (connector->multithreadedClients) {
            stopAndReleaseThreadedClient(client);
        }
        else {
            notifyConnectionShutdown(client, &connector->jmethods);
            releaseClient(client);
        }
    }
}

static bool XConnectorEpoll_stopEpollThread(XConnectorEpoll* connector, bool destroyRequested);

static void closeConnectorResources(JNIEnv* env, XConnectorEpoll* connector) {
    removeFdFromEpoll(connector->epollFd, connector->serverFd);
    closeOwnedFd(&connector->serverFd);
    removeFdFromEpoll(connector->epollFd, connector->shutdownFd);
    closeOwnedFd(&connector->shutdownFd);

    if (connector->jmethods.obj) {
        (*env)->DeleteGlobalRef(env, connector->jmethods.obj);
        connector->jmethods.obj = NULL;
    }

    closeOwnedFd(&connector->epollFd);
}

static void freeConnectorStorage(XConnectorEpoll* connector) {
    if (connector->clientsChangedInitialized) pthread_cond_destroy(&connector->clientsChanged);
    if (connector->clientsMutexInitialized) pthread_mutex_destroy(&connector->clientsMutex);
    free(connector);
}

static bool XConnectorEpoll_destroy(JNIEnv* env, XConnectorEpoll* connector) {
    if (!connector) return true;
    if (!XConnectorEpoll_stopEpollThread(connector, true)) return false;

    closeConnectorResources(env, connector);
    freeConnectorStorage(connector);
    return true;
}

static XConnectorEpoll* XConnectorEpoll_allocate(JNIEnv* env, jobject obj, const char* sockPath) {
    XConnectorEpoll* connector = calloc(1, sizeof(XConnectorEpoll));
    if (!connector) return NULL;
    connector->epollFd = -1;
    connector->serverFd = -1;
    connector->shutdownFd = -1;

    if (pthread_mutex_init(&connector->clientsMutex, NULL) != 0) goto error;
    connector->clientsMutexInitialized = true;
    if (pthread_cond_init(&connector->clientsChanged, NULL) != 0) goto error;
    connector->clientsChangedInitialized = true;

    connector->epollFd = epoll_create1(EPOLL_CLOEXEC);
    if (connector->epollFd < 0) goto error;

    connector->serverFd = createServerSocket(sockPath);
    if (connector->serverFd < 0) goto error;

    connector->shutdownFd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    if (connector->shutdownFd < 0) goto error;

    if (!addFdToEpoll(connector->epollFd, connector->serverFd, &connector->serverFd)) goto error;
    if (!addFdToEpoll(connector->epollFd, connector->shutdownFd, &connector->shutdownFd)) goto error;

    (*env)->GetJavaVM(env, &connector->jmethods.jvm);
    connector->jmethods.obj = (*env)->NewGlobalRef(env, obj);
    if (!connector->jmethods.obj) goto error;
    return connector;

error:
    XConnectorEpoll_destroy(env, connector);
    return NULL;
}

static void XConnectorEpoll_killConnection(XConnectorEpoll* connector, ConnectedClient* requested) {
    if (!connector || !requested) return;

    pthread_mutex_lock(&connector->clientsMutex);
    ConnectedClient* client = findClientLocked(connector, requested);
    if (!client) {
        pthread_mutex_unlock(&connector->clientsMutex);
        return;
    }

    bool selfCleanup = connector->multithreadedClients && client->pollThreadStarted &&
                       pthread_equal(pthread_self(), client->pollThread);
    if (selfCleanup) {
        if (client->cleanupOwner == CLIENT_CLEANUP_NONE) {
            client->cleanupOwner = CLIENT_CLEANUP_REAPER;
        }
        client->running = false;
        pthread_mutex_unlock(&connector->clientsMutex);
        return;
    }

    if (client->cleanupOwner == CLIENT_CLEANUP_EXTERNAL) {
        pthread_mutex_unlock(&connector->clientsMutex);
        return;
    }

    client->cleanupOwner = CLIENT_CLEANUP_EXTERNAL;
    client->running = false;
    pthread_mutex_unlock(&connector->clientsMutex);

    if (connector->multithreadedClients) {
        stopAndReleaseThreadedClient(client);
    }
    else {
        notifyConnectionShutdown(client, &connector->jmethods);
        releaseClient(client);
    }
}

static void* pollThread(void* param) {
    ConnectedClient* client = param;
    loadJMethods(&client->jmethods);
    JMethods* jmethods = &client->jmethods;
    client->tag = (*jmethods->env)->CallObjectMethod(jmethods->env, jmethods->obj,
                                                    jmethods->handleNewConnection,
                                                    (jlong)client, client->fd);

    while (clientIsRunning(client)) {
        int res = waitForSocketRead(client->fd, client->shutdownFd);
        if (res != 1) break;
        (*jmethods->env)->CallVoidMethod(jmethods->env, jmethods->obj,
                                        jmethods->handleExistingConnection, client->tag);
    }

    notifyConnectionShutdown(client, jmethods);
    (*jmethods->jvm)->DetachCurrentThread(jmethods->jvm);

    XConnectorEpoll* connector = client->connector;
    pthread_mutex_lock(&connector->clientsMutex);
    client->running = false;
    client->exited = true;
    if (client->cleanupOwner == CLIENT_CLEANUP_NONE) {
        client->cleanupOwner = CLIENT_CLEANUP_REAPER;
    }
    pthread_mutex_unlock(&connector->clientsMutex);
    requestShutdown(connector->shutdownFd);
    return NULL;
}

static void XConnectorEpoll_handleNewConnection(XConnectorEpoll* connector, int clientFd) {
    JMethods* jmethods = &connector->jmethods;
    ConnectedClient* client = calloc(1, sizeof(ConnectedClient));
    if (!client) {
        closeOwnedFd(&clientFd);
        return;
    }

    client->fd = clientFd;
    client->shutdownFd = -1;
    client->running = true;
    client->connector = connector;

    if (connector->multithreadedClients) {
        client->jmethods.jvm = connector->jmethods.jvm;
        client->jmethods.obj = connector->jmethods.obj;
        client->shutdownFd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
        if (client->shutdownFd < 0) goto refusal;

        pthread_mutex_lock(&connector->clientsMutex);
        addClientLocked(connector, client);
        if (pthread_create(&client->pollThread, NULL, pollThread, client) != 0) {
            removeClientLocked(connector, client);
            pthread_mutex_unlock(&connector->clientsMutex);
            goto refusal;
        }
        client->pollThreadStarted = true;
        pthread_mutex_unlock(&connector->clientsMutex);
    }
    else {
        if (!addFdToEpoll(connector->epollFd, clientFd, client)) goto refusal;
        pthread_mutex_lock(&connector->clientsMutex);
        addClientLocked(connector, client);
        pthread_mutex_unlock(&connector->clientsMutex);
        client->tag = (*jmethods->env)->CallObjectMethod(jmethods->env, jmethods->obj,
                                                        jmethods->handleNewConnection,
                                                        (jlong)client, clientFd);
    }
    return;

refusal:
    closeOwnedFd(&client->shutdownFd);
    closeOwnedFd(&client->fd);
    free(client);
}

static void* epollThread(void* param) {
    XConnectorEpoll* connector = param;
    JMethods* jmethods = &connector->jmethods;
    loadJMethods(jmethods);
    struct epoll_event events[MAX_EVENTS] = {0};

    while (connectorIsRunning(connector)) {
        int numFds;
        do {
            numFds = epoll_wait(connector->epollFd, events, MAX_EVENTS, -1);
        } while (numFds < 0 && errno == EINTR);
        if (numFds < 0) break;

        for (int i = 0; i < numFds && connectorIsRunning(connector); i++) {
            if (events[i].data.ptr == &connector->serverFd) {
                int clientFd = accept4(connector->serverFd, NULL, NULL, SOCK_CLOEXEC);
                if (clientFd >= 0) XConnectorEpoll_handleNewConnection(connector, clientFd);
            }
            else if (events[i].data.ptr == &connector->shutdownFd) {
                drainShutdownFd(connector->shutdownFd);
                reapExitedClients(connector);
            }
            else if (events[i].events & (EPOLLIN | EPOLLERR | EPOLLHUP | EPOLLRDHUP)) {
                ConnectedClient* client = events[i].data.ptr;
                (*jmethods->env)->CallVoidMethod(jmethods->env, jmethods->obj,
                                                jmethods->handleExistingConnection, client->tag);
            }
        }
    }

    drainClients(connector);
    pthread_mutex_lock(&connector->clientsMutex);
    connector->running = false;
    bool destroyOnExit = connector->destroyOnListenerExit;
    pthread_mutex_unlock(&connector->clientsMutex);

    if (destroyOnExit) {
        JavaVM* jvm = jmethods->jvm;
        JNIEnv* env = jmethods->env;
        jobject completionTarget = (*env)->NewLocalRef(env, jmethods->obj);
        jmethodID completionMethod = jmethods->handleNativeDestroyComplete;
        pthread_detach(pthread_self());
        closeConnectorResources(env, connector);
        freeConnectorStorage(connector);
        if (completionTarget) {
            (*env)->CallVoidMethod(env, completionTarget, completionMethod);
            (*env)->DeleteLocalRef(env, completionTarget);
        }
        (*jvm)->DetachCurrentThread(jvm);
        return NULL;
    }
    (*jmethods->jvm)->DetachCurrentThread(jmethods->jvm);
    return NULL;
}

static void XConnectorEpoll_startEpollThread(XConnectorEpoll* connector, bool multithreadedClients) {
    pthread_mutex_lock(&connector->clientsMutex);
    if (connector->epollThreadStarted) {
        pthread_mutex_unlock(&connector->clientsMutex);
        return;
    }
    connector->running = true;
    connector->multithreadedClients = multithreadedClients;
    if (pthread_create(&connector->epollThread, NULL, epollThread, connector) == 0) {
        connector->epollThreadStarted = true;
    }
    else connector->running = false;
    pthread_mutex_unlock(&connector->clientsMutex);
}

static bool XConnectorEpoll_stopEpollThread(XConnectorEpoll* connector, bool destroyRequested) {
    if (!connector || !connector->clientsMutexInitialized) return true;

    pthread_mutex_lock(&connector->clientsMutex);
    if (!connector->epollThreadStarted) {
        pthread_mutex_unlock(&connector->clientsMutex);
        return true;
    }
    connector->running = false;
    pthread_t epollThreadId = connector->epollThread;
    bool isListenerThread = pthread_equal(pthread_self(), epollThreadId);
    bool isClientThread = false;
    if (connector->multithreadedClients) {
        for (ConnectedClient* client = connector->clients; client; client = client->next) {
            if (client->pollThreadStarted && pthread_equal(pthread_self(), client->pollThread)) {
                isClientThread = true;
                break;
            }
        }
    }
    bool isOwnedThread = isListenerThread || isClientThread;
    if (destroyRequested && isOwnedThread) connector->destroyOnListenerExit = true;
    pthread_mutex_unlock(&connector->clientsMutex);

    requestShutdown(connector->shutdownFd);
    if (isOwnedThread) return false;

    pthread_join(epollThreadId, NULL);
    pthread_mutex_lock(&connector->clientsMutex);
    connector->epollThreadStarted = false;
    memset(&connector->epollThread, 0, sizeof(connector->epollThread));
    pthread_mutex_unlock(&connector->clientsMutex);
    return true;
}

JNIEXPORT void JNICALL
Java_com_winlator_xconnector_XConnectorEpoll_closeFd(jint fd) {
    int ownedFd = fd;
    closeOwnedFd(&ownedFd);
}

JNIEXPORT jlong JNICALL
Java_com_winlator_xconnector_XConnectorEpoll_nativeAllocate(JNIEnv* env, jobject obj,
                                                            jstring sockPath) {
    const char* pathPtr = (*env)->GetStringUTFChars(env, sockPath, 0);
    if (!pathPtr) return 0;
    XConnectorEpoll* connector = XConnectorEpoll_allocate(env, obj, pathPtr);

    (*env)->ReleaseStringUTFChars(env, sockPath, pathPtr);
    return (jlong)connector;
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_xconnector_XConnectorEpoll_destroy(JNIEnv *env, jobject obj, jlong nativePtr) {
    return XConnectorEpoll_destroy(env, (XConnectorEpoll*)nativePtr);
}

JNIEXPORT void JNICALL
Java_com_winlator_xconnector_XConnectorEpoll_startEpollThread(JNIEnv *env, jobject obj,
                                                              jlong nativePtr, jboolean multithreadedClients) {
    XConnectorEpoll_startEpollThread((XConnectorEpoll*)nativePtr, multithreadedClients);
}

JNIEXPORT void JNICALL
Java_com_winlator_xconnector_XConnectorEpoll_stopEpollThread(JNIEnv *env, jobject obj,
                                                             jlong nativePtr) {
    XConnectorEpoll_stopEpollThread((XConnectorEpoll*)nativePtr, false);
}

JNIEXPORT void JNICALL
Java_com_winlator_xconnector_XConnectorEpoll_killConnection(JNIEnv *env, jclass obj,
                                                            jlong connectorPtr, jlong clientPtr) {
    XConnectorEpoll_killConnection((XConnectorEpoll*)connectorPtr, (ConnectedClient*)clientPtr);
}
