/* SPDX-License-Identifier: GPL-2.0 */
#ifndef _LINUX_AI_H
#define _LINUX_AI_H

#include <linux/list.h>
#include <linux/types.h>
#include <linux/spinlock.h>

#define AI_MAX_NAME_LEN 64

struct ai_client {
	struct list_head list;
	pid_t            pid;
	bool             is_daemon;
	char             name[AI_MAX_NAME_LEN];
};

/* Exported from ai_core.c */
extern struct list_head ai_client_list;
extern spinlock_t       ai_client_lock;

int  ai_register_client(struct ai_client *client);
void ai_unregister_client(struct ai_client *client);
struct proc_dir_entry *ai_get_proc_root(void);

#endif /* _LINUX_AI_H */
