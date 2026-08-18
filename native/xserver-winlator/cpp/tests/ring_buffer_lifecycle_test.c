#include <assert.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include "ring_buffer.h"

typedef struct ReaderState {
    RingBuffer* ring;
    bool result;
} ReaderState;

static void* readEmptyRing(void* opaque) {
    ReaderState* state = opaque;
    uint64_t value = 0;
    state->result = RingBuffer_read(state->ring, &value, sizeof(value));
    return NULL;
}

static RingBuffer* createRing(int* fdOut) {
    int fd = memfd_create("vortek-ring-lifecycle-test", MFD_CLOEXEC);
    assert(fd >= 0);
    assert(ftruncate(fd, RingBuffer_getSHMemSize(4096)) == 0);
    RingBuffer* ring = RingBuffer_create(fd, 4096);
    assert(ring != NULL);
    *fdOut = fd;
    return ring;
}

static void joinWithin250ms(pthread_t thread) {
    struct timespec deadline;
    assert(clock_gettime(CLOCK_REALTIME, &deadline) == 0);
    deadline.tv_nsec += 250 * 1000 * 1000;
    if (deadline.tv_nsec >= 1000 * 1000 * 1000) {
        deadline.tv_sec++;
        deadline.tv_nsec -= 1000 * 1000 * 1000;
    }
    assert(pthread_timedjoin_np(thread, NULL, &deadline) == 0);
}

static void testPeerLossStopsEmptyReader(void) {
    int fd;
    RingBuffer* ring = createRing(&fd);
    int sockets[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, sockets) == 0);
    RingBuffer_setPeerFd(ring, sockets[0]);

    ReaderState state = {.ring = ring, .result = true};
    pthread_t thread;
    assert(pthread_create(&thread, NULL, readEmptyRing, &state) == 0);
    usleep(10 * 1000);
    close(sockets[1]);
    joinWithin250ms(thread);
    assert(!state.result);

    close(sockets[0]);
    RingBuffer_free(ring);
    close(fd);
}

static void testExitStatusStopsEmptyReader(void) {
    int fd;
    RingBuffer* ring = createRing(&fd);
    ReaderState state = {.ring = ring, .result = true};
    pthread_t thread;
    assert(pthread_create(&thread, NULL, readEmptyRing, &state) == 0);
    usleep(10 * 1000);
    RingBuffer_setStatus(ring, RING_STATUS_EXIT);
    joinWithin250ms(thread);
    assert(!state.result);

    RingBuffer_free(ring);
    close(fd);
}

int main(void) {
    testPeerLossStopsEmptyReader();
    testExitStatusStopsEmptyReader();
    puts("ring_buffer_lifecycle_test: PASS");
    return 0;
}
