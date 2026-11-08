/* The upload half of "copy + upload ≤ 4 ms" (spec §10), measured the way skiko's GL backend uploads a changed bitmap:
 * glTexSubImage2D on the same Mesa driver (EGL surfaceless + GLES2), a full frame and a small dirty rectangle, each
 * followed by glFinish. Test only: built with -DWV_GLBENCH. */
#include "wv.h"
#ifdef WV_GLBENCH
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int compare(const void *a, const void *b)
{
    int64_t x = *(const int64_t *)a, y = *(const int64_t *)b;
    return (x > y) - (x < y);
}

static int64_t percentile(int64_t *values, int n, int p)
{
    qsort(values, (size_t)n, sizeof *values, compare);
    int rank = (p * n + 99) / 100;
    if (rank < 1)
        rank = 1;
    if (rank > n)
        rank = n;
    return values[rank - 1];
}

/* out: [0] full p50 ns, [1] full p95, [2] dirty p50, [3] dirty p95, [4] 1 if BGRA was uploaded as is. 0 on success. */
int wv_upload_bench(int width, int height, int dirty_width, int dirty_height, int runs, int64_t *out, char *renderer, int renderer_len)
{
    if (runs < 1 || width <= dirty_width || height <= dirty_height)
        return -9;
    PFNEGLGETPLATFORMDISPLAYEXTPROC get_display = (PFNEGLGETPLATFORMDISPLAYEXTPROC)eglGetProcAddress("eglGetPlatformDisplayEXT");
    if (!get_display)
        return -1;
    EGLDisplay dpy = get_display(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL);
    if (dpy == EGL_NO_DISPLAY || !eglInitialize(dpy, NULL, NULL))
        return -2;
    eglBindAPI(EGL_OPENGL_ES_API);
    EGLint config_attrs[] = { EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE };
    EGLConfig config;
    EGLint count = 0;
    if (!eglChooseConfig(dpy, config_attrs, &config, 1, &count) || count < 1) {
        eglTerminate(dpy);
        return -3;
    }
    EGLint context_attrs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    EGLContext ctx = eglCreateContext(dpy, config, EGL_NO_CONTEXT, context_attrs);
    if (ctx == EGL_NO_CONTEXT || !eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx)) {
        eglTerminate(dpy);
        return -4;
    }
    const char *extensions = (const char *)glGetString(GL_EXTENSIONS);
    int bgra = extensions && strstr(extensions, "GL_EXT_texture_format_BGRA8888") != NULL;
    GLenum format = bgra ? GL_BGRA_EXT : GL_RGBA;
    const char *name = (const char *)glGetString(GL_RENDERER);
    snprintf(renderer, (size_t)renderer_len, "%s", name ? name : "?");
    GLuint texture = 0;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexImage2D(GL_TEXTURE_2D, 0, (GLint)format, width, height, 0, format, GL_UNSIGNED_BYTE, NULL);
    size_t full_size = (size_t)width * height * 4, dirty_size = (size_t)dirty_width * dirty_height * 4;
    uint8_t *full = malloc(full_size), *dirty = malloc(dirty_size);
    int64_t *full_ns = calloc((size_t)runs, sizeof(int64_t)), *dirty_ns = calloc((size_t)runs, sizeof(int64_t));
    memset(full, 0x80, full_size);
    memset(dirty, 0x40, dirty_size);
    glFinish();
    for (int i = 0; i < runs; i++) {
        full[((size_t)i * 4099) % full_size] ^= 0xff; /* new pixels each time, as a changed bitmap has */
        int64_t t0 = wv_now_ns();
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, format, GL_UNSIGNED_BYTE, full);
        glFinish();
        full_ns[i] = wv_now_ns() - t0;
        dirty[((size_t)i * 131) % dirty_size] ^= 0xff;
        t0 = wv_now_ns();
        glTexSubImage2D(GL_TEXTURE_2D, 0, (i * 37) % (width - dirty_width), (i * 23) % (height - dirty_height),
            dirty_width, dirty_height, format, GL_UNSIGNED_BYTE, dirty);
        glFinish();
        dirty_ns[i] = wv_now_ns() - t0;
    }
    out[0] = percentile(full_ns, runs, 50);
    out[1] = percentile(full_ns, runs, 95);
    out[2] = percentile(dirty_ns, runs, 50);
    out[3] = percentile(dirty_ns, runs, 95);
    out[4] = bgra;
    glDeleteTextures(1, &texture);
    free(full);
    free(dirty);
    free(full_ns);
    free(dirty_ns);
    eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(dpy, ctx);
    eglTerminate(dpy);
    return 0;
}
#endif
