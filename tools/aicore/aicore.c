// SPDX-License-Identifier: GPL-2.0
/*
 * aicore — userspace AI daemon with OS control
 *
 * Reads /etc/aicore/config.json for API key, then:
 *   - Registers with the kernel via /proc/ai/register
 *   - Listens on /run/aicore.sock for client connections (dte)
 *   - Forwards chat messages to Claude API, with a run_shell tool
 *     that lets the model execute commands and see results
 *   - Streams the final response back to the connected client
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <curl/curl.h>

#include "ui_html.h"   /* static const char HTTP_HTML[] — generated from ui.html */

#define SOCKET_PATH   "/run/aicore.sock"
#define HTTP_PORT      8080
#define CONFIG_PATH   "/etc/aicore/config.json"
#define PROC_REGISTER "/proc/ai/register"
#define PROC_METRICS  "/proc/ai/metrics"
#define API_URL       "https://api.anthropic.com/v1/messages"
#define ANTHROPIC_VER "2023-06-01"

#define BUF_SIZE      8192
#define MAX_API_KEY   256
#define MAX_MODEL     64
#define MAX_PROMPT    4096
#define MAX_COMMAND   2048   /* max shell command length */
#define MAX_OUTPUT    4096   /* max command output returned to API */
#define MAX_TURNS     50     /* max tool-use rounds before giving up */
#define MSG_BUF_SIZE  262144 /* 256 KB conversation history buffer */

/*
 * Tool definitions sent to the API on every request.
 *
 *  run_shell  — non-interactive commands, returns stdout+stderr
 *  launch_app — interactive TUI apps (lynx, vim, htop …); switches the
 *               user's console to tty3, waits for exit, returns to tty1
 */
#define TOOL_DEF \
	"[{\"name\":\"run_shell\"," \
	"\"description\":\"Run a non-interactive shell command and return " \
	"stdout+stderr. Use for: installing packages, reading/writing files, " \
	"inspecting system state, running background daemons, etc. " \
	"Do NOT use for interactive TUI apps — use launch_app for those.\"," \
	"\"input_schema\":{\"type\":\"object\"," \
	"\"properties\":{\"command\":{\"type\":\"string\"," \
	"\"description\":\"Shell command to run\"}}," \
	"\"required\":[\"command\"]}}," \
	"{\"name\":\"launch_app\"," \
	"\"description\":\"Launch an interactive TUI application (lynx, vim, nano, " \
	"htop, mc, less, etc.) on tty3. The user's display switches to tty3 so " \
	"they can interact with the app. Returns after the user exits the app " \
	"and the display switches back to the AI on tty1. " \
	"Always use this instead of run_shell for any app that needs a terminal.\"," \
	"\"input_schema\":{\"type\":\"object\"," \
	"\"properties\":{\"command\":{\"type\":\"string\"," \
	"\"description\":\"Full command to launch (e.g. 'lynx https://example.com', 'vim /etc/hosts')\"}}," \
	"\"required\":[\"command\"]}}]"

/* ------------------------------------------------------------------ */
/* Config                                                              */
/* ------------------------------------------------------------------ */

struct config {
	char api_key[MAX_API_KEY];
	char model[MAX_MODEL];
	int  max_tokens;
};

static struct config cfg;

/* Very small JSON value extractor — searches for "key": then reads string */
static int json_get_string(const char *json, const char *key, char *out, int out_sz)
{
	char search[128];
	const char *p, *start, *end;

	snprintf(search, sizeof(search), "\"%s\":", key);
	p = strstr(json, search);
	if (!p)
		return -1;

	p += strlen(search);
	while (*p == ' ' || *p == '\t')
		p++;

	if (*p != '"')
		return -1;
	p++;

	start = p;
	end = strchr(p, '"');
	if (!end)
		return -1;

	int len = (int)(end - start);
	if (len >= out_sz)
		len = out_sz - 1;

	memcpy(out, start, len);
	out[len] = '\0';
	return 0;
}

static int json_get_int(const char *json, const char *key, int *out)
{
	char search[128];
	const char *p;

	snprintf(search, sizeof(search), "\"%s\":", key);
	p = strstr(json, search);
	if (!p)
		return -1;

	p += strlen(search);
	while (*p == ' ' || *p == '\t')
		p++;

	*out = atoi(p);
	return 0;
}

static int load_config(void)
{
	FILE *f;
	char buf[4096];
	size_t n;

	f = fopen(CONFIG_PATH, "r");
	if (!f) {
		fprintf(stderr, "aicore: cannot open %s: %s\n",
			CONFIG_PATH, strerror(errno));
		return -1;
	}

	n = fread(buf, 1, sizeof(buf) - 1, f);
	fclose(f);
	buf[n] = '\0';

	/* Defaults */
	strncpy(cfg.model, "claude-sonnet-4-6", sizeof(cfg.model) - 1);
	cfg.max_tokens = 2048;

	if (json_get_string(buf, "api_key", cfg.api_key, sizeof(cfg.api_key)) < 0) {
		fprintf(stderr, "aicore: missing api_key in config\n");
		return -1;
	}
	json_get_string(buf, "model",     cfg.model,    sizeof(cfg.model));
	json_get_int(buf,    "max_tokens", &cfg.max_tokens);

	return 0;
}

/* ------------------------------------------------------------------ */
/* Kernel registration                                                 */
/* ------------------------------------------------------------------ */

static void register_with_kernel(void)
{
	int fd = open(PROC_REGISTER, O_WRONLY);
	if (fd < 0) {
		fprintf(stderr, "aicore: /proc/ai/register unavailable: %s\n",
			strerror(errno));
		return;
	}
	write(fd, "1", 1);
	close(fd);
}

/* ------------------------------------------------------------------ */
/* System metrics from kernel                                          */
/* ------------------------------------------------------------------ */

static int read_metrics(char *out, int out_sz)
{
	FILE *f = fopen(PROC_METRICS, "r");
	if (!f)
		return -1;

	size_t n = fread(out, 1, out_sz - 1, f);
	fclose(f);
	out[n] = '\0';
	return 0;
}

/* ------------------------------------------------------------------ */
/* CURL helpers                                                        */
/* ------------------------------------------------------------------ */

struct curl_buf {
	char  *data;
	size_t len;
	size_t cap;
};

static size_t curl_write_cb(char *ptr, size_t size, size_t nmemb, void *userdata)
{
	struct curl_buf *b = userdata;
	size_t incoming = size * nmemb;

	if (b->len + incoming + 1 > b->cap) {
		size_t newcap = b->cap + incoming + 4096;
		char *tmp = realloc(b->data, newcap);
		if (!tmp)
			return 0;
		b->data = tmp;
		b->cap  = newcap;
	}
	memcpy(b->data + b->len, ptr, incoming);
	b->len += incoming;
	b->data[b->len] = '\0';
	return incoming;
}

/* ------------------------------------------------------------------ */
/* JSON escaping                                                       */
/* ------------------------------------------------------------------ */

static void json_escape(const char *in, char *out, int out_sz)
{
	int j = 0;
	for (int i = 0; in[i] && j < out_sz - 2; i++) {
		unsigned char c = in[i];
		if (c == '"' || c == '\\') {
			if (j + 2 > out_sz - 1) break;
			out[j++] = '\\'; out[j++] = c;
		} else if (c == '\n') {
			if (j + 2 > out_sz - 1) break;
			out[j++] = '\\'; out[j++] = 'n';
		} else if (c == '\r') {
			if (j + 2 > out_sz - 1) break;
			out[j++] = '\\'; out[j++] = 'r';
		} else if (c == '\t') {
			if (j + 2 > out_sz - 1) break;
			out[j++] = '\\'; out[j++] = 't';
		} else if (c < 0x20) {
			/* skip other control characters */
		} else {
			out[j++] = c;
		}
	}
	out[j] = '\0';
}

/* ------------------------------------------------------------------ */
/* Shell tool                                                          */
/* ------------------------------------------------------------------ */

static void run_shell(const char *cmd, char *out, int out_sz)
{
	/* Merge stderr into stdout so errors are visible to the model */
	char full[MAX_COMMAND + 8];
	snprintf(full, sizeof(full), "%s 2>&1", cmd);

	FILE *f = popen(full, "r");
	if (!f) {
		snprintf(out, out_sz, "(popen error: %s)", strerror(errno));
		return;
	}
	size_t n = fread(out, 1, out_sz - 1, f);
	pclose(f);
	out[n] = '\0';

	/* Trim trailing newline */
	if (n > 0 && out[n - 1] == '\n')
		out[--n] = '\0';
}

/*
 * Detect whether a Wayland compositor socket exists.
 * Returns the WAYLAND_DISPLAY value (e.g. "wayland-1") or NULL.
 * Writes into buf (must be at least 32 bytes).
 */
static const char *find_wayland_display(char *buf, size_t buf_sz)
{
	static const char *runtime = "/run/user/0";
	struct stat st;

	/* Primary: read the socket name written by sway on startup.
	 * Trust the file — sway wrote it with the correct WAYLAND_DISPLAY. */
	FILE *wf = fopen("/run/wayland-display", "r");
	if (wf) {
		buf[0] = '\0';
		if (fgets(buf, (int)buf_sz, wf))
			buf[strcspn(buf, "\r\n")] = '\0';
		fclose(wf);
		if (buf[0])
			return buf;
	}

	/* Fallback: scan wayland-0..9 */
	for (int try = 0; try < 3; try++) {
		for (int i = 0; i <= 9; i++) {
			char path[64];
			snprintf(path, sizeof(path), "%s/wayland-%d", runtime, i);
			if (stat(path, &st) == 0 && S_ISSOCK(st.st_mode)) {
				snprintf(buf, buf_sz, "wayland-%d", i);
				return buf;
			}
		}
		sleep(1);
	}
	return NULL;
}

/*
 * Launch an application.
 *
 * If a Wayland compositor is running:
 *   Spawn the app with the correct WAYLAND_DISPLAY / XDG_RUNTIME_DIR env
 *   variables and return immediately — the window appears in the compositor.
 *
 * Otherwise (text console):
 *   Switch to tty3, run the app there (TUI), then switch back to tty1.
 *   Blocks until the user exits the app.
 */
static void launch_app(const char *cmd, char *out, int out_sz)
{
	char wayland_display[32];
	const char *wl = find_wayland_display(wayland_display, sizeof(wayland_display));

	if (wl) {
		/*
		 * Wayland path — non-blocking graphical launch.
		 * Set env vars that cover GTK, Qt, Firefox, SDL, etc.
		 */
		char full[MAX_COMMAND + 512];
		snprintf(full, sizeof(full),
			"XDG_RUNTIME_DIR=/run/user/0 "
			"WAYLAND_DISPLAY=%s "
			"MOZ_ENABLE_WAYLAND=1 "
			"GDK_BACKEND=wayland "
			"QT_QPA_PLATFORM=wayland "
			"SDL_VIDEODRIVER=wayland "
			"WLR_NO_HARDWARE_CURSORS=1 "
			"nohup %s >/var/log/app-launch.log 2>&1 &",
			wl, cmd);

		FILE *f = popen(full, "r");
		if (f) pclose(f);

		snprintf(out, out_sz,
			"launched '%s' in Wayland session (%s) — "
			"window should appear in sway",
			cmd, wl);
	} else {
		/*
		 * No Wayland — TUI path on tty3.
		 * chvt 3 → run app → chvt 1.
		 */
		static const char script_path[] = "/tmp/.aicore_launch.sh";

		FILE *f = fopen(script_path, "w");
		if (!f) {
			snprintf(out, out_sz, "error: %s", strerror(errno));
			return;
		}
		fprintf(f,
			"#!/bin/sh\n"
			"[ -c /dev/tty3 ] || mknod -m 600 /dev/tty3 c 4 3\n"
			"export TERM=linux\n"
			"export TERMINFO=/usr/share/terminfo\n"
			"chvt 3 2>/dev/null\n"
			"printf '\\033[2J\\033[H' > /dev/tty3\n");
		fprintf(f, "%s </dev/tty3 >/dev/tty3 2>/dev/tty3\n", cmd);
		fprintf(f, "chvt 1 2>/dev/null\n");
		fclose(f);
		chmod(script_path, 0755);

		FILE *p = popen(script_path, "r");
		if (!p) {
			snprintf(out, out_sz, "launch error: %s", strerror(errno));
			return;
		}
		char tmp[256];
		while (fread(tmp, 1, sizeof(tmp), p) > 0)
			;
		pclose(p);

		snprintf(out, out_sz, "app exited, display returned to AI interface");
	}
}

/* ------------------------------------------------------------------ */
/* Single HTTP POST to the Claude API                                  */
/* ------------------------------------------------------------------ */

/*
 * Posts body to API_URL and returns an allocated response string,
 * or NULL on network/curl error (errbuf filled).
 */
static char *api_post(const char *body, char *errbuf)
{
	CURL *curl;
	CURLcode res;
	struct curl_slist *headers = NULL;
	struct curl_buf response = { NULL, 0, 0 };

	curl = curl_easy_init();
	if (!curl)
		return NULL;

	response.data = malloc(16384);
	if (!response.data) {
		curl_easy_cleanup(curl);
		return NULL;
	}
	response.cap = 16384;

	char auth_header[MAX_API_KEY + 32];
	snprintf(auth_header, sizeof(auth_header), "x-api-key: %s", cfg.api_key);

	headers = curl_slist_append(headers, "Content-Type: application/json");
	headers = curl_slist_append(headers, auth_header);
	headers = curl_slist_append(headers, "anthropic-version: " ANTHROPIC_VER);

	errbuf[0] = '\0';
	curl_easy_setopt(curl, CURLOPT_URL,           API_URL);
	curl_easy_setopt(curl, CURLOPT_HTTPHEADER,    headers);
	curl_easy_setopt(curl, CURLOPT_POSTFIELDS,    body);
	curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, curl_write_cb);
	curl_easy_setopt(curl, CURLOPT_WRITEDATA,     &response);
	curl_easy_setopt(curl, CURLOPT_TIMEOUT,       60L);
	curl_easy_setopt(curl, CURLOPT_CAINFO,        "/etc/ssl/certs/ca-certificates.crt");
	curl_easy_setopt(curl, CURLOPT_ERRORBUFFER,   errbuf);

	res = curl_easy_perform(curl);
	curl_slist_free_all(headers);
	curl_easy_cleanup(curl);

	if (res != CURLE_OK) {
		if (errbuf[0] == '\0')
			snprintf(errbuf, CURL_ERROR_SIZE, "%s", curl_easy_strerror(res));
		fprintf(stderr, "aicore: curl error: %s\n", errbuf);
		free(response.data);
		return NULL;
	}

	return response.data; /* caller frees */
}

/* ------------------------------------------------------------------ */
/* Agentic Claude call                                                 */
/* ------------------------------------------------------------------ */

/*
 * Sends user_message to Claude with the run_shell tool available.
 * Loops: if Claude calls run_shell, we execute the command and send
 * the result back, until Claude produces a final text response.
 *
 * Returns an allocated string with the final reply, or NULL on error.
 */
static char *call_claude(const char *user_message, char *errbuf)
{
	char *messages = malloc(MSG_BUF_SIZE);
	char *body     = malloc(MSG_BUF_SIZE + 8192);
	char *reply    = NULL;

	if (!messages || !body) {
		free(messages);
		free(body);
		return NULL;
	}

	/* Build (and escape) system prompt */
	char metrics[512] = "";
	read_metrics(metrics, sizeof(metrics));

	char sys_raw[1024], sys_esc[2048];
	snprintf(sys_raw, sizeof(sys_raw),
		"You are an AI assistant embedded in a Linux system with full OS control. "
		"Use the run_shell tool to interact with the system — inspect state, "
		"run programs, edit files, manage processes, configure networking, etc. "
		"You are running as root on Alpine Linux with BusyBox. "
		"Be concise. Prefer running commands over guessing about system state.\n"
		"GRAPHICAL APPS: Use launch_app for any GUI or TUI application. "
		"Sway (Wayland compositor) is ALWAYS running on this system — "
		"launch_app auto-detects the Wayland socket and spawns the app there. "
		"NEVER start, restart, kill, or reconfigure sway — it is managed by the system init.\n"
		"Current kernel metrics:\n%s", metrics);
	json_escape(sys_raw, sys_esc, sizeof(sys_esc));

	/* Initial messages array: just the user turn */
	char user_esc[MAX_PROMPT * 2];
	json_escape(user_message, user_esc, sizeof(user_esc));
	snprintf(messages, MSG_BUF_SIZE,
		"[{\"role\":\"user\",\"content\":\"%s\"}]", user_esc);

	for (int turn = 0; turn < MAX_TURNS; turn++) {

		/* Assemble request body */
		int body_len = snprintf(body, MSG_BUF_SIZE + 8192,
			"{\"model\":\"%s\","
			"\"max_tokens\":%d,"
			"\"system\":\"%s\","
			"\"tools\":" TOOL_DEF ","
			"\"messages\":%s}",
			cfg.model, cfg.max_tokens, sys_esc, messages);

		if (body_len < 0) {
			reply = strdup("(body build error)");
			break;
		}

		char *resp = api_post(body, errbuf);
		if (!resp)
			break;

		fprintf(stderr, "aicore turn %d: %.256s\n", turn, resp);

		/* What did the model decide to do? */
		char stop_reason[32] = "";
		json_get_string(resp, "stop_reason", stop_reason, sizeof(stop_reason));

		if (strcmp(stop_reason, "tool_use") == 0) {
			/*
			 * Find the tool_use block and extract tool id + command.
			 * Response content: [{"type":"tool_use","id":"toolu_x","name":"run_shell","input":{"command":"..."}}]
			 */
			char tool_id[64]        = "";
			char tool_name[32]      = "";
			char command[MAX_COMMAND] = "";

			const char *tu = strstr(resp, "\"type\":\"tool_use\"");
			if (tu) {
				json_get_string(tu, "id",   tool_id, sizeof(tool_id));
				json_get_string(tu, "name", tool_name, sizeof(tool_name));
				const char *inp = strstr(tu, "\"input\":");
				if (inp)
					json_get_string(inp, "command", command, sizeof(command));
			}

			free(resp);

			if (!command[0]) {
				reply = strdup("(could not parse tool call)");
				break;
			}

			/* Dispatch to the right tool */
			char cmd_out[MAX_OUTPUT] = "";
			if (strcmp(tool_name, "launch_app") == 0) {
				fprintf(stderr, "aicore launch_app[%d]: %s\n", turn, command);
				launch_app(command, cmd_out, sizeof(cmd_out));
			} else {
				fprintf(stderr, "aicore shell[%d]: %s\n", turn, command);
				run_shell(command, cmd_out, sizeof(cmd_out));
			}
			fprintf(stderr, "=> %.128s\n", cmd_out);

			/* Escape everything for JSON */
			char id_esc[128], cmd_esc[MAX_COMMAND * 2], out_esc[MAX_OUTPUT * 2];
			json_escape(tool_id, id_esc,  sizeof(id_esc));
			json_escape(command, cmd_esc, sizeof(cmd_esc));
			json_escape(cmd_out, out_esc, sizeof(out_esc));

			/*
			 * Extend the messages array:
			 *   strip the trailing ']', append the assistant tool_use turn
			 *   and the user tool_result turn, then close with ']'.
			 */
			size_t cur = strlen(messages);
			if (cur < 1 || messages[cur - 1] != ']') {
				reply = strdup("(messages format error)");
				break;
			}
			messages[cur - 1] = '\0'; /* remove trailing ] */

			/*
			 * Use malloc so large outputs don't overflow the stack.
			 * id_esc: ~80, tool_name: ~32, cmd_esc: up to 4096,
			 * out_esc: up to 8192, template: ~200 → ~12600 bytes.
			 */
			size_t ext_cap = strlen(id_esc) * 2
					+ strlen(tool_name) + strlen(cmd_esc)
					+ strlen(out_esc) + 512;
			char *ext = malloc(ext_cap);
			if (!ext) {
				reply = strdup("(out of memory)");
				break;
			}
			int ext_len = snprintf(ext, ext_cap,
				",{\"role\":\"assistant\",\"content\":["
				  "{\"type\":\"tool_use\","
				   "\"id\":\"%s\","
				   "\"name\":\"%s\","
				   "\"input\":{\"command\":\"%s\"}}]},"
				"{\"role\":\"user\",\"content\":["
				  "{\"type\":\"tool_result\","
				   "\"tool_use_id\":\"%s\","
				   "\"content\":\"%s\"}]}]",
				id_esc, tool_name, cmd_esc, id_esc, out_esc);

			if (ext_len < 0 || cur + (size_t)ext_len >= MSG_BUF_SIZE) {
				free(ext);
				reply = strdup("(context too long)");
				break;
			}
			strncat(messages, ext, MSG_BUF_SIZE - cur - 1);
			free(ext);

		} else {
			/* end_turn (or unexpected) — extract the text reply */
			char text[MAX_PROMPT] = "";
			json_get_string(resp, "text", text, sizeof(text));

			if (text[0]) {
				reply = strdup(text);
			} else {
				char raw[420];
				snprintf(raw, sizeof(raw), "RAW: %.400s", resp);
				reply = strdup(raw);
			}
			free(resp);
			break;
		}
	}

	if (!reply) {
		if (errbuf[0])
			reply = strdup(errbuf);
		else
			reply = strdup("(reached max tool steps — check /var/log/aicore.log on tty2)");
	}

	free(messages);
	free(body);
	return reply;
}

/* ------------------------------------------------------------------ */
/* HTTP server (port 8080) — serves web UI + /api/chat endpoint       */
/* ------------------------------------------------------------------ */

static void write_all(int fd, const char *buf, size_t len)
{
	size_t done = 0;
	while (done < len) {
		ssize_t n = write(fd, buf + done, len - done);
		if (n <= 0) break;
		done += n;
	}
}

static void http_respond(int fd, int code, const char *ctype,
			 const char *body, size_t blen)
{
	const char *reason = (code == 200) ? "OK" :
			     (code == 204) ? "No Content" :
			     (code == 400) ? "Bad Request" :
			     (code == 404) ? "Not Found" : "Error";
	char hdr[256];
	int hlen = snprintf(hdr, sizeof(hdr),
		"HTTP/1.1 %d %s\r\n"
		"Content-Type: %s\r\n"
		"Content-Length: %zu\r\n"
		"Connection: close\r\n"
		"Access-Control-Allow-Origin: *\r\n"
		"\r\n",
		code, reason, ctype, blen);
	write_all(fd, hdr, hlen);
	if (body && blen)
		write_all(fd, body, blen);
}

static void *http_handle_conn(void *arg)
{
	int fd = *(int *)arg;
	free(arg);

	/* Read request (single recv is enough for typical browser requests) */
	char buf[16384];
	ssize_t n = recv(fd, buf, sizeof(buf) - 1, 0);
	if (n <= 0) { close(fd); return NULL; }
	buf[n] = '\0';

	/* Parse method + path */
	char method[8] = "", path[256] = "";
	sscanf(buf, "%7s %255s", method, path);

	if (strcmp(path, "/") == 0 && strcmp(method, "GET") == 0) {
		/* Serve the embedded web UI */
		http_respond(fd, 200, "text/html; charset=utf-8",
			     HTTP_HTML, strlen(HTTP_HTML));

	} else if (strcmp(path, "/api/ping") == 0) {
		http_respond(fd, 204, "text/plain", "", 0);

	} else if (strcmp(path, "/api/chat") == 0 && strcmp(method, "POST") == 0) {
		/* Find body after blank line */
		const char *body = strstr(buf, "\r\n\r\n");
		if (!body) {
			http_respond(fd, 400, "application/json",
				     "{\"ok\":false,\"error\":\"no body\"}", 29);
			close(fd); return NULL;
		}
		body += 4;

		char message[MAX_PROMPT] = "";
		json_get_string(body, "message", message, sizeof(message));
		if (!message[0]) {
			http_respond(fd, 400, "application/json",
				     "{\"ok\":false,\"error\":\"empty message\"}", 36);
			close(fd); return NULL;
		}

		char errbuf[CURL_ERROR_SIZE];
		char *reply = call_claude(message, errbuf);

		if (reply) {
			/* Build JSON: {"ok":true,"reply":"<escaped>"} */
			size_t resc_sz = strlen(reply) * 2 + 64;
			char *resc = malloc(resc_sz);
			if (resc) {
				json_escape(reply, resc, (int)resc_sz);
				size_t jsz = strlen(resc) + 32;
				char *js = malloc(jsz);
				if (js) {
					int jl = snprintf(js, jsz,
						"{\"ok\":true,\"reply\":\"%s\"}", resc);
					http_respond(fd, 200, "application/json", js, jl);
					free(js);
				}
				free(resc);
			}
			free(reply);
		} else {
			char esc[CURL_ERROR_SIZE * 2];
			json_escape(errbuf[0] ? errbuf : "API call failed",
				    esc, sizeof(esc));
			char js[sizeof(esc) + 32];
			int jl = snprintf(js, sizeof(js),
				"{\"ok\":false,\"error\":\"%s\"}", esc);
			http_respond(fd, 500, "application/json", js, jl);
		}

	} else if (strcmp(method, "OPTIONS") == 0) {
		http_respond(fd, 204, "text/plain", "", 0);

	} else {
		http_respond(fd, 404, "application/json",
			     "{\"ok\":false,\"error\":\"not found\"}", 31);
	}

	close(fd);
	return NULL;
}

static void *http_server_thread(void *arg)
{
	(void)arg;

	int srv = socket(AF_INET, SOCK_STREAM, 0);
	if (srv < 0) {
		fprintf(stderr, "aicore: HTTP socket: %s\n", strerror(errno));
		return NULL;
	}

	int opt = 1;
	setsockopt(srv, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

	struct sockaddr_in addr;
	memset(&addr, 0, sizeof(addr));
	addr.sin_family      = AF_INET;
	addr.sin_addr.s_addr = INADDR_ANY;
	addr.sin_port        = htons(HTTP_PORT);

	if (bind(srv, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
		fprintf(stderr, "aicore: HTTP bind: %s\n", strerror(errno));
		close(srv); return NULL;
	}
	listen(srv, 16);
	fprintf(stderr, "aicore: HTTP UI on http://localhost:%d\n", HTTP_PORT);

	while (1) {
		int *cfd = malloc(sizeof(int));
		if (!cfd) continue;
		*cfd = accept(srv, NULL, NULL);
		if (*cfd < 0) { free(cfd); continue; }

		pthread_t tid;
		pthread_attr_t attr;
		pthread_attr_init(&attr);
		pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
		pthread_create(&tid, &attr, http_handle_conn, cfd);
		pthread_attr_destroy(&attr);
	}
	close(srv);
	return NULL;
}

/* ------------------------------------------------------------------ */
/* Client handler (one thread per connection)                          */
/* ------------------------------------------------------------------ */

static void *handle_client(void *arg)
{
	int fd = *(int *)arg;
	free(arg);

	char buf[MAX_PROMPT];

	while (1) {
		ssize_t n = recv(fd, buf, sizeof(buf) - 1, 0);
		if (n <= 0)
			break;
		buf[n] = '\0';

		/* Strip trailing newline */
		if (n > 0 && buf[n - 1] == '\n')
			buf[n - 1] = '\0';

		char errbuf[CURL_ERROR_SIZE];
		char *reply = call_claude(buf, errbuf);
		if (reply) {
			send(fd, reply, strlen(reply), 0);
			send(fd, "\n", 1, 0);
			free(reply);
		} else {
			FILE *nf = fopen("/var/log/network.log", "r");
			char netinfo[512] = "";
			if (nf) {
				fread(netinfo, 1, sizeof(netinfo) - 1, nf);
				fclose(nf);
				for (char *p = netinfo; *p; p++)
					if (*p == '\n') *p = '|';
			}
			char err[CURL_ERROR_SIZE + 600];
			snprintf(err, sizeof(err), "ERROR: %s | net: %s\n",
				 errbuf[0] ? errbuf : "API call failed", netinfo);
			send(fd, err, strlen(err), 0);
		}
	}

	close(fd);
	return NULL;
}

/* ------------------------------------------------------------------ */
/* Main / daemonise                                                    */
/* ------------------------------------------------------------------ */

static int server_fd = -1;

static void sig_handler(int sig)
{
	(void)sig;
	if (server_fd >= 0) {
		close(server_fd);
		unlink(SOCKET_PATH);
	}
	_exit(0);
}

static void daemonise(void)
{
	pid_t pid = fork();
	if (pid < 0) {
		perror("fork");
		exit(1);
	}
	if (pid > 0)
		exit(0); /* parent exits */

	setsid();

	int devnull = open("/dev/null", O_RDWR);
	if (devnull >= 0) {
		dup2(devnull, STDIN_FILENO);
		dup2(devnull, STDOUT_FILENO);
		close(devnull);
	}
	int logfd = open("/var/log/aicore.log", O_WRONLY | O_CREAT | O_APPEND, 0644);
	if (logfd >= 0) {
		dup2(logfd, STDERR_FILENO);
		close(logfd);
	}
}

int main(int argc, char *argv[])
{
	int foreground = 0;

	for (int i = 1; i < argc; i++) {
		if (strcmp(argv[i], "-f") == 0 || strcmp(argv[i], "--foreground") == 0)
			foreground = 1;
	}

	if (load_config() < 0)
		return 1;

	curl_global_init(CURL_GLOBAL_ALL);
	register_with_kernel();

	unlink(SOCKET_PATH);

	server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
	if (server_fd < 0) {
		perror("socket");
		return 1;
	}

	struct sockaddr_un addr;
	memset(&addr, 0, sizeof(addr));
	addr.sun_family = AF_UNIX;
	strncpy(addr.sun_path, SOCKET_PATH, sizeof(addr.sun_path) - 1);

	if (bind(server_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
		perror("bind");
		return 1;
	}

	chmod(SOCKET_PATH, 0666);
	listen(server_fd, 8);

	signal(SIGTERM, sig_handler);
	signal(SIGINT,  sig_handler);
	signal(SIGPIPE, SIG_IGN);

	if (!foreground)
		daemonise();

	/* Start HTTP server thread (web UI on port 8080) */
	{
		pthread_t htid;
		pthread_attr_t hattr;
		pthread_attr_init(&hattr);
		pthread_attr_setdetachstate(&hattr, PTHREAD_CREATE_DETACHED);
		pthread_create(&htid, &hattr, http_server_thread, NULL);
		pthread_attr_destroy(&hattr);
	}

	while (1) {
		int *cfd = malloc(sizeof(int));
		if (!cfd)
			continue;

		*cfd = accept(server_fd, NULL, NULL);
		if (*cfd < 0) {
			free(cfd);
			if (errno == EINTR)
				continue;
			break;
		}

		pthread_t tid;
		pthread_attr_t attr;
		pthread_attr_init(&attr);
		pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
		pthread_create(&tid, &attr, handle_client, cfd);
		pthread_attr_destroy(&attr);
	}

	curl_global_cleanup();
	unlink(SOCKET_PATH);
	return 0;
}
