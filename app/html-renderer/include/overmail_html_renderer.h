// The C side of src/ffi.rs: html mails rendered by Blitz, for the iOS app.
#pragma once
#include <stddef.h>
#include <stdint.h>

int64_t blitz_open(const char *html, int32_t css_width, int32_t viewport_height, float scale);
int32_t blitz_height(int64_t handle);
uint8_t *blitz_render(int64_t handle, int32_t top, int32_t height, size_t *out_len);
void blitz_free_buffer(uint8_t *buffer, size_t len);
char *blitz_link_at(int64_t handle, float x, float y);
void blitz_free_string(char *string);
void blitz_close(int64_t handle);
