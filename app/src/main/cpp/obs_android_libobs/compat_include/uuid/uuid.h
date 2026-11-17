#pragma once

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef unsigned char uuid_t[16];

static inline void uuid_generate_random(uuid_t out) {
    arc4random_buf(out, 16);
    out[6] = (unsigned char)((out[6] & 0x0F) | 0x40);
    out[8] = (unsigned char)((out[8] & 0x3F) | 0x80);
}

static inline void uuid_generate(uuid_t out) {
    uuid_generate_random(out);
}

static inline void uuid_unparse_lower(const uuid_t uu, char *out) {
    snprintf(
        out,
        37,
        "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
        uu[0], uu[1], uu[2], uu[3],
        uu[4], uu[5],
        uu[6], uu[7],
        uu[8], uu[9],
        uu[10], uu[11], uu[12], uu[13], uu[14], uu[15]
    );
}

static inline void uuid_unparse(const uuid_t uu, char *out) {
    uuid_unparse_lower(uu, out);
}

#ifdef __cplusplus
}
#endif
