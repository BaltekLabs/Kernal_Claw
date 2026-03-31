// SPDX-License-Identifier: GPL-2.0
/*
 * AI Core Kernel Subsystem
 *
 * Manages AI client registration and exposes /proc/ai/ interface.
 * Userspace daemon (aicore) registers here on startup so the kernel
 * can track it and surface system metrics.
 */

#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/init.h>
#include <linux/slab.h>
#include <linux/proc_fs.h>
#include <linux/seq_file.h>
#include <linux/spinlock.h>
#include <linux/uaccess.h>

#include "ai.h"

LIST_HEAD(ai_client_list);
EXPORT_SYMBOL(ai_client_list);

DEFINE_SPINLOCK(ai_client_lock);
EXPORT_SYMBOL(ai_client_lock);

static struct proc_dir_entry *ai_proc_root;

/* ------------------------------------------------------------------ */
/* Client registration API                                             */
/* ------------------------------------------------------------------ */

int ai_register_client(struct ai_client *client)
{
	if (!client)
		return -EINVAL;

	spin_lock(&ai_client_lock);
	list_add_tail(&client->list, &ai_client_list);
	spin_unlock(&ai_client_lock);

	return 0;
}
EXPORT_SYMBOL(ai_register_client);

void ai_unregister_client(struct ai_client *client)
{
	if (!client)
		return;

	spin_lock(&ai_client_lock);
	list_del(&client->list);
	spin_unlock(&ai_client_lock);
}
EXPORT_SYMBOL(ai_unregister_client);

struct proc_dir_entry *ai_get_proc_root(void)
{
	return ai_proc_root;
}
EXPORT_SYMBOL(ai_get_proc_root);

/* ------------------------------------------------------------------ */
/* /proc/ai/status                                                     */
/* ------------------------------------------------------------------ */

static int ai_status_show(struct seq_file *m, void *v)
{
	struct ai_client *client;
	int count = 0;

	seq_puts(m, "AI Core Subsystem\n");
	seq_puts(m, "=================\n");

	spin_lock(&ai_client_lock);
	list_for_each_entry(client, &ai_client_list, list) {
		seq_printf(m, "client[%d]: pid=%d name=%s daemon=%s\n",
			   count++, client->pid, client->name,
			   client->is_daemon ? "yes" : "no");
	}
	spin_unlock(&ai_client_lock);

	seq_printf(m, "total_clients: %d\n", count);
	return 0;
}

static int ai_status_open(struct inode *inode, struct file *file)
{
	return single_open(file, ai_status_show, NULL);
}

static const struct proc_ops ai_status_ops = {
	.proc_open    = ai_status_open,
	.proc_read    = seq_read,
	.proc_lseek   = seq_lseek,
	.proc_release = single_release,
};

/* ------------------------------------------------------------------ */
/* /proc/ai/register  (write to register the calling process)         */
/* ------------------------------------------------------------------ */

static ssize_t ai_register_write(struct file *file, const char __user *buf,
				 size_t count, loff_t *ppos)
{
	struct ai_client *client;

	client = kzalloc(sizeof(*client), GFP_KERNEL);
	if (!client)
		return -ENOMEM;

	client->pid       = current->pid;
	client->is_daemon = true;
	/*
	 * Bug fix: removed `client->flags = IS_DAEMON;` here — the flags
	 * field does not exist on struct ai_client.  Setting is_daemon=true
	 * above is the correct way to mark this client as a daemon.
	 */
	snprintf(client->name, AI_MAX_NAME_LEN, "aicore[%d]", client->pid);

	ai_register_client(client);

	pr_info("ai_core: registered daemon client pid=%d\n", client->pid);
	return count;
}

static const struct proc_ops ai_register_ops = {
	.proc_write  = ai_register_write,
	.proc_lseek  = noop_llseek,
};

/* ------------------------------------------------------------------ */
/* Module init / exit                                                  */
/* ------------------------------------------------------------------ */

static int __init ai_core_init(void)
{
	ai_proc_root = proc_mkdir("ai", NULL);
	if (!ai_proc_root) {
		pr_err("ai_core: failed to create /proc/ai\n");
		return -ENOMEM;
	}

	if (!proc_create("status", 0444, ai_proc_root, &ai_status_ops)) {
		pr_err("ai_core: failed to create /proc/ai/status\n");
		goto err;
	}

	if (!proc_create("register", 0200, ai_proc_root, &ai_register_ops)) {
		pr_err("ai_core: failed to create /proc/ai/register\n");
		goto err;
	}

	pr_info("ai_core: AI subsystem initialized\n");
	return 0;

err:
	proc_remove(ai_proc_root);
	return -ENOMEM;
}

static void __exit ai_core_exit(void)
{
	struct ai_client *client, *tmp;

	proc_remove(ai_proc_root);

	spin_lock(&ai_client_lock);
	list_for_each_entry_safe(client, tmp, &ai_client_list, list) {
		list_del(&client->list);
		kfree(client);
	}
	spin_unlock(&ai_client_lock);

	pr_info("ai_core: AI subsystem removed\n");
}

module_init(ai_core_init);
module_exit(ai_core_exit);

MODULE_LICENSE("GPL");
MODULE_AUTHOR("Linux AI Project");
MODULE_DESCRIPTION("AI core kernel subsystem");
