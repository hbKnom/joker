#ifndef JOKER_CLOUDFLARED_BRIDGE_H
#define JOKER_CLOUDFLARED_BRIDGE_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void *joker_tunnel_handle;

typedef enum {
    JOKER_TUNNEL_STOPPED = 0,
    JOKER_TUNNEL_STARTING = 1,
    JOKER_TUNNEL_CONNECTED = 2,
    JOKER_TUNNEL_RECONNECTING = 3,
    JOKER_TUNNEL_FAILED = 4,
    JOKER_TUNNEL_STOPPING = 5,
    JOKER_TUNNEL_UNSUPPORTED = 6,
} joker_tunnel_status_code;

typedef void (*joker_callback)(void *user, int status, const char *url, const char *error);

joker_tunnel_handle joker_tunnel_start_quick(const char *origin, joker_callback callback, void *user);
joker_tunnel_handle joker_tunnel_start_token(const char *token, const char *origin, joker_callback callback, void *user);
int joker_tunnel_begin_login(joker_tunnel_handle handle, joker_callback callback, void *user);
int joker_tunnel_select_existing(joker_tunnel_handle handle, const char *tunnel_id, const char *hostname);
int joker_tunnel_stop(joker_tunnel_handle handle);
int joker_tunnel_status(joker_tunnel_handle handle, char *buffer, size_t buffer_len);

#ifdef __cplusplus
}
#endif

#endif
