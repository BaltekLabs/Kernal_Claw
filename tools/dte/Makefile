# SPDX-License-Identifier: GPL-2.0
# Makefile for dte — AI terminal interface

CC      ?= gcc
CFLAGS  ?= -O2 -Wall -I../../include -D_GNU_SOURCE
LDFLAGS ?= -lncursesw -lpthread

TARGET  := dte
SRCS    := dte.c
OBJS    := $(SRCS:.c=.o)

DESTDIR    ?=
INSTALL_BIN := $(DESTDIR)/usr/bin

.PHONY: all clean install-rootfs

all: $(TARGET)

$(TARGET): $(OBJS)
	$(CC) $(CFLAGS) -o $@ $^ $(LDFLAGS)

%.o: %.c
	$(CC) $(CFLAGS) -c -o $@ $<

install-rootfs: $(TARGET)
	install -D -m 755 $(TARGET) ../../rootfs$(INSTALL_BIN)/$(TARGET)

clean:
	rm -f $(OBJS) $(TARGET)
