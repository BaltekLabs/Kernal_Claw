// SPDX-License-Identifier: GPL-2.0
/*
 * dte — AI daemon terminal emulator / chat interface
 *
 * Connects to aicore via /run/aicore.sock and presents a full-screen
 * ncurses chat UI.  Runs on tty1; init restarts it on exit.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <ncursesw/ncurses.h>

#define SOCKET_PATH "/run/aicore.sock"
#define MAX_INPUT   1024
#define MAX_LINES   4096
#define MAX_LINE_LEN 512

/* ------------------------------------------------------------------ */
/* Chat history                                                        */
/* ------------------------------------------------------------------ */

static char  history[MAX_LINES][MAX_LINE_LEN];
static int   hist_count = 0;
static pthread_mutex_t hist_mutex = PTHREAD_MUTEX_INITIALIZER;

static void hist_add(const char *prefix, const char *text)
{
	pthread_mutex_lock(&hist_mutex);
	if (hist_count < MAX_LINES) {
		snprintf(history[hist_count], MAX_LINE_LEN, "%s%s", prefix, text);
		hist_count++;
	}
	pthread_mutex_unlock(&hist_mutex);
}

/* ------------------------------------------------------------------ */
/* ncurses windows                                                     */
/* ------------------------------------------------------------------ */

static WINDOW *win_chat;   /* scrolling chat area */
static WINDOW *win_input;  /* single-line input bar */
static int     win_rows, win_cols;

static void ui_init(void)
{
	initscr();
	cbreak();
	noecho();
	keypad(stdscr, TRUE);
	getmaxyx(stdscr, win_rows, win_cols);

	/* Chat window: all rows except the last two (status + input) */
	win_chat  = newwin(win_rows - 2, win_cols, 0, 0);
	win_input = newwin(2, win_cols, win_rows - 2, 0);

	scrollok(win_chat, TRUE);
	idlok(win_chat, TRUE);

	if (has_colors()) {
		start_color();
		init_pair(1, COLOR_GREEN,  COLOR_BLACK); /* user */
		init_pair(2, COLOR_CYAN,   COLOR_BLACK); /* assistant */
		init_pair(3, COLOR_YELLOW, COLOR_BLACK); /* system */
	}
}

static void ui_draw_chat(void)
{
	pthread_mutex_lock(&hist_mutex);
	werase(win_chat);

	int start = hist_count > (win_rows - 2) ? hist_count - (win_rows - 2) : 0;
	int row = 0;
	for (int i = start; i < hist_count; i++, row++) {
		wmove(win_chat, row, 0);
		if (strncmp(history[i], "You: ", 5) == 0) {
			wattron(win_chat, COLOR_PAIR(1) | A_BOLD);
			waddnstr(win_chat, history[i], win_cols - 1);
			wattroff(win_chat, COLOR_PAIR(1) | A_BOLD);
		} else if (strncmp(history[i], "AI:  ", 5) == 0) {
			wattron(win_chat, COLOR_PAIR(2));
			waddnstr(win_chat, history[i], win_cols - 1);
			wattroff(win_chat, COLOR_PAIR(2));
		} else {
			wattron(win_chat, COLOR_PAIR(3));
			waddnstr(win_chat, history[i], win_cols - 1);
			wattroff(win_chat, COLOR_PAIR(3));
		}
	}
	pthread_mutex_unlock(&hist_mutex);
	wrefresh(win_chat);
}

static void ui_draw_input(const char *prompt, const char *text)
{
	werase(win_input);
	wattron(win_input, A_REVERSE);
	mvwprintw(win_input, 0, 0, "%-*s", win_cols, " DTE — AI Terminal  (Ctrl-C to quit)");
	wattroff(win_input, A_REVERSE);
	mvwprintw(win_input, 1, 0, "%s%s", prompt, text);
	wrefresh(win_input);
}

/* ------------------------------------------------------------------ */
/* Socket connection                                                   */
/* ------------------------------------------------------------------ */

static int connect_to_aicore(void)
{
	int fd;
	struct sockaddr_un addr;

	fd = socket(AF_UNIX, SOCK_STREAM, 0);
	if (fd < 0)
		return -1;

	memset(&addr, 0, sizeof(addr));
	addr.sun_family = AF_UNIX;
	strncpy(addr.sun_path, SOCKET_PATH, sizeof(addr.sun_path) - 1);

	if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
		close(fd);
		return -1;
	}
	return fd;
}

/* ------------------------------------------------------------------ */
/* Main interaction loop                                               */
/* ------------------------------------------------------------------ */

int main(void)
{
	int sock = -1;
	char input[MAX_INPUT];
	int  input_len = 0;
	char response[MAX_INPUT * 8];

	ui_init();

	hist_add("*** ", "DTE — AI Terminal Interface");
	hist_add("*** ", "Connecting to aicore...");
	ui_draw_chat();
	ui_draw_input("> ", "");

	/* Retry connecting a few times (aicore may still be starting) */
	for (int attempt = 0; attempt < 10 && sock < 0; attempt++) {
		sock = connect_to_aicore();
		if (sock < 0)
			sleep(1);
	}

	if (sock < 0) {
		hist_add("ERR ", "Cannot connect to aicore. Is it running?");
		ui_draw_chat();
		ui_draw_input("> ", "");
		sleep(3);
		endwin();
		return 1;
	}

	hist_add("*** ", "Connected. Type your message and press Enter.");
	ui_draw_chat();

	while (1) {
		ui_draw_input("> ", input);

		int ch = wgetch(win_input);

		if (ch == '\n' || ch == KEY_ENTER) {
			if (input_len == 0)
				continue;

			input[input_len] = '\0';
			hist_add("You: ", input);
			ui_draw_chat();

			/* Send to aicore */
			char msg[MAX_INPUT + 2];
			snprintf(msg, sizeof(msg), "%s\n", input);
			if (send(sock, msg, strlen(msg), 0) < 0) {
				hist_add("ERR ", "Send failed — reconnecting...");
				close(sock);
				sock = connect_to_aicore();
				if (sock < 0) {
					hist_add("ERR ", "Reconnect failed.");
					ui_draw_chat();
					break;
				}
				send(sock, msg, strlen(msg), 0);
			}

			/* Wait for response */
			hist_add("AI:  ", "(thinking...)");
			ui_draw_chat();

			ssize_t n = recv(sock, response, sizeof(response) - 1, 0);
			if (n <= 0) {
				/* Remove "thinking..." entry */
				pthread_mutex_lock(&hist_mutex);
				if (hist_count > 0) hist_count--;
				pthread_mutex_unlock(&hist_mutex);
				hist_add("ERR ", "Connection lost.");
				ui_draw_chat();
				break;
			}
			response[n] = '\0';

			/* Remove trailing newline */
			if (n > 0 && response[n - 1] == '\n')
				response[n - 1] = '\0';

			/* Replace the "thinking..." line */
			pthread_mutex_lock(&hist_mutex);
			if (hist_count > 0) hist_count--;
			pthread_mutex_unlock(&hist_mutex);

			/* Word-wrap long responses */
			char *p = response;
			while (*p) {
				char line[MAX_LINE_LEN];
				int len = win_cols - 5;  /* leave room for "AI:  " */
				if (len <= 0) len = 60;
				if ((int)strlen(p) <= len) {
					hist_add("AI:  ", p);
					break;
				}
				/* Find last space within len */
				int cut = len;
				for (int k = len; k > 0; k--) {
					if (p[k] == ' ') { cut = k; break; }
				}
				strncpy(line, p, cut);
				line[cut] = '\0';
				hist_add("AI:  ", line);
				p += cut;
				while (*p == ' ') p++;
			}

			ui_draw_chat();

			/* Clear input */
			input[0]  = '\0';
			input_len = 0;

		} else if (ch == KEY_BACKSPACE || ch == 127 || ch == '\b') {
			if (input_len > 0)
				input[--input_len] = '\0';

		} else if (ch == 3 || ch == 4) {  /* Ctrl-C / Ctrl-D */
			break;

		} else if (ch >= 32 && ch < 127 && input_len < MAX_INPUT - 1) {
			input[input_len++] = (char)ch;
			input[input_len]   = '\0';
		}
	}

	if (sock >= 0)
		close(sock);

	endwin();
	return 0;
}
