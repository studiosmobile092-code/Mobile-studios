#pragma once

#include <pthread.h>

#ifdef __cplusplus
extern "C" {
#endif

#ifndef PTHREAD_CANCEL_ENABLE
#define PTHREAD_CANCEL_ENABLE 0
#endif
#ifndef PTHREAD_CANCEL_DISABLE
#define PTHREAD_CANCEL_DISABLE 1
#endif
#ifndef PTHREAD_CANCEL_DEFERRED
#define PTHREAD_CANCEL_DEFERRED 0
#endif
#ifndef PTHREAD_CANCEL_ASYNCHRONOUS
#define PTHREAD_CANCEL_ASYNCHRONOUS 1
#endif

#ifdef __ANDROID__
static inline int obs_android_pthread_setcancelstate(int state, int *oldstate) {
    (void)state;
    if (oldstate) *oldstate = PTHREAD_CANCEL_ENABLE;
    return 0;
}

static inline int obs_android_pthread_setcanceltype(int type, int *oldtype) {
    (void)type;
    if (oldtype) *oldtype = PTHREAD_CANCEL_DEFERRED;
    return 0;
}

static inline int obs_android_pthread_cancel(pthread_t thread) {
    (void)thread;
    return 0;
}

#define pthread_setcancelstate obs_android_pthread_setcancelstate
#define pthread_setcanceltype obs_android_pthread_setcanceltype
#define pthread_cancel obs_android_pthread_cancel
#endif

#ifdef __cplusplus
}
#endif
