# SPDX-License-Identifier: GPL-2.0
# Makefile for aicore — AI daemon

CC      ?= gcc
CFLAGS  ?= -O2 -Wall -I../../include -D_GNU_SOURCE
LDFLAGS ?= -lcurl -lssl -lcrypto -lz -lpthread

TARGET  := aicore
SRCS    := aicore.c
OBJS    := $(SRCS:.c=.o)

DESTDIR      ?=
INSTALL_SBIN := $(DESTDIR)/usr/sbin

.PHONY: all clean install-rootfs

all: $(TARGET)

# Embed ui.html as a C string constant
ui_html.h: ui.html
	awk 'BEGIN{print "static const char HTTP_HTML[] ="} \
	     {gsub(/\\/,"\\\\"); gsub(/"/,"\\\""); printf "  \"%s\\n\"\n", $$0} \
	     END{print ";"}' ui.html > $@

aicore.o: aicore.c ui_html.h
	$(CC) $(CFLAGS) -c -o $@ $<

$(TARGET): $(OBJS)
	$(CC) $(CFLAGS) -o $@ $^ $(LDFLAGS)

install-rootfs: $(TARGET)
	install -D -m 755 $(TARGET) ../../rootfs$(INSTALL_SBIN)/$(TARGET)

clean:
	rm -f $(OBJS) $(TARGET) ui_html.h
