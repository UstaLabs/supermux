/*
 * pty-helper: minimal PTY bridge for claudemux web terminal.
 *
 * Spawns a shell inside a real PTY and relays I/O to stdin/stdout.
 * Resize protocol: a NUL byte followed by "R<cols>:<rows>\n" on stdin
 * triggers ioctl(TIOCSWINSZ) on the PTY master.
 *
 * Usage: pty-helper <cols> <rows> <workdir> [cmd [args...]]
 *   With a cmd + args, the child execs that argv verbatim (e.g. a tmux
 *   attach: `tmux -L muxterm new-session -A ...`). With no cmd, it falls
 *   back to a login shell ($SHELL or /bin/bash), preserving old behavior.
 * Exits with the child's exit code.
 */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#ifdef __APPLE__
#include <util.h>   /* forkpty lives in <util.h> on macOS (there is no <pty.h>) */
#else
#include <pty.h>    /* forkpty on glibc/Linux */
#endif
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static volatile pid_t child_pid = 0;

static void sigchld_handler(int sig) {
    (void)sig;
}

int main(int argc, char *argv[]) {
    if (argc < 4) {
        fprintf(stderr, "usage: pty-helper <cols> <rows> <workdir> [cmd [args...]]\n");
        return 1;
    }

    int cols = atoi(argv[1]);
    int rows = atoi(argv[2]);
    const char *workdir = argv[3];

    if (cols < 1) cols = 80;
    if (rows < 1) rows = 24;

    struct winsize ws = { .ws_row = rows, .ws_col = cols };
    int master;
    pid_t pid = forkpty(&master, NULL, NULL, &ws);

    if (pid < 0) {
        perror("forkpty");
        return 1;
    }

    if (pid == 0) {
        /* Child: exec the requested command (or a login shell) in workdir */
        if (chdir(workdir) != 0) {
            perror("chdir");
            _exit(1);
        }
        setenv("TERM", "xterm-256color", 1);
        setenv("COLORTERM", "truecolor", 1);
        if (argc > 4) {
            /* Explicit command + args, e.g. `tmux -L muxterm new-session ...` */
            execvp(argv[4], &argv[4]);
        } else {
            const char *shell = getenv("SHELL");
            if (!shell) shell = "/bin/bash";
            execlp(shell, shell, "-l", (char *)NULL);
        }
        perror("exec");
        _exit(1);
    }

    /* Parent: relay between stdin/stdout and PTY master */
    child_pid = pid;

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = sigchld_handler;
    sa.sa_flags = SA_NOCLDSTOP;
    sigaction(SIGCHLD, &sa, NULL);

    /* Non-blocking master fd */
    int flags = fcntl(master, F_GETFL);
    fcntl(master, F_SETFL, flags | O_NONBLOCK);

    /* Non-blocking stdin */
    flags = fcntl(STDIN_FILENO, F_GETFL);
    fcntl(STDIN_FILENO, F_SETFL, flags | O_NONBLOCK);

    struct pollfd fds[2];
    fds[0].fd = STDIN_FILENO;
    fds[0].events = POLLIN;
    fds[1].fd = master;
    fds[1].events = POLLIN;

    char buf[4096];
    char resize_buf[64];
    int resize_pos = 0;
    int in_resize = 0;
    int running = 1;

    while (running) {
        int ret = poll(fds, 2, 1000);
        if (ret < 0) {
            if (errno == EINTR) {
                /* Check if child exited */
                int status;
                if (waitpid(pid, &status, WNOHANG) > 0) {
                    /* Drain remaining output */
                    while (1) {
                        ssize_t n = read(master, buf, sizeof(buf));
                        if (n <= 0) break;
                        write(STDOUT_FILENO, buf, n);
                    }
                    close(master);
                    if (WIFEXITED(status))
                        return WEXITSTATUS(status);
                    return 1;
                }
                continue;
            }
            break;
        }

        /* stdin → PTY master (with resize protocol) */
        if (fds[0].revents & POLLIN) {
            ssize_t n = read(STDIN_FILENO, buf, sizeof(buf));
            if (n <= 0) {
                running = 0;
                break;
            }
            ssize_t i = 0;
            while (i < n) {
                if (in_resize) {
                    if (buf[i] == '\n' || resize_pos >= (int)sizeof(resize_buf) - 1) {
                        resize_buf[resize_pos] = '\0';
                        if (resize_buf[0] == 'R') {
                            int c = 0, r = 0;
                            if (sscanf(resize_buf + 1, "%d:%d", &c, &r) == 2 &&
                                c > 0 && c < 500 && r > 0 && r < 200) {
                                struct winsize nws = { .ws_row = r, .ws_col = c };
                                ioctl(master, TIOCSWINSZ, &nws);
                                kill(pid, SIGWINCH);
                            }
                        }
                        in_resize = 0;
                        resize_pos = 0;
                        i++;
                        continue;
                    }
                    resize_buf[resize_pos++] = buf[i];
                    i++;
                    continue;
                }
                if (buf[i] == '\0') {
                    /* Flush any pending normal data before this NUL */
                    /* NUL byte starts resize escape */
                    in_resize = 1;
                    resize_pos = 0;
                    i++;
                    continue;
                }
                /* Find span of normal data (up to next NUL) */
                ssize_t start = i;
                while (i < n && buf[i] != '\0') i++;
                write(master, buf + start, i - start);
            }
        }
        if (fds[0].revents & (POLLHUP | POLLERR)) {
            running = 0;
            break;
        }

        /* PTY master → stdout */
        if (fds[1].revents & POLLIN) {
            ssize_t n = read(master, buf, sizeof(buf));
            if (n > 0) {
                write(STDOUT_FILENO, buf, n);
            }
        }
        if (fds[1].revents & (POLLHUP | POLLERR)) {
            /* Child probably exited */
            int status;
            if (waitpid(pid, &status, WNOHANG) > 0) {
                /* Drain remaining */
                while (1) {
                    ssize_t nr = read(master, buf, sizeof(buf));
                    if (nr <= 0) break;
                    write(STDOUT_FILENO, buf, nr);
                }
                close(master);
                if (WIFEXITED(status))
                    return WEXITSTATUS(status);
                return 1;
            }
            running = 0;
        }
    }

    kill(pid, SIGHUP);
    int status;
    waitpid(pid, &status, 0);
    close(master);
    if (WIFEXITED(status))
        return WEXITSTATUS(status);
    return 1;
}
