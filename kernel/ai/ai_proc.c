// SPDX-License-Identifier: GPL-2.0
/*
 * AI /proc metrics interface
 *
 * Exposes system metrics via /proc/ai/metrics for the userspace
 * AI daemon so it can include live system context in prompts.
 *
 * Bug fix: added #include <linux/sched/loadavg.h> to resolve
 * undefined references to avenrun[] and FSHIFT.
 */

#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/proc_fs.h>
#include <linux/seq_file.h>
#include <linux/mm.h>
#include <linux/sched/loadavg.h>   /* avenrun[], FSHIFT, LOAD_INT, LOAD_FRAC */
#include <linux/jiffies.h>

#include "ai.h"

/* ------------------------------------------------------------------ */
/* /proc/ai/metrics                                                    */
/* ------------------------------------------------------------------ */

static int ai_metrics_show(struct seq_file *m, void *v)
{
	unsigned long avnrun[3];
	struct sysinfo si;

	/* Load averages — avenrun[] is defined in sched/loadavg.h */
	get_avenrun(avnrun, FIXED_1 / 200, 0);

	seq_printf(m, "load_avg_1min:  %lu.%02lu\n",
		   LOAD_INT(avnrun[0]), LOAD_FRAC(avnrun[0]));
	seq_printf(m, "load_avg_5min:  %lu.%02lu\n",
		   LOAD_INT(avnrun[1]), LOAD_FRAC(avnrun[1]));
	seq_printf(m, "load_avg_15min: %lu.%02lu\n",
		   LOAD_INT(avnrun[2]), LOAD_FRAC(avnrun[2]));

	/* Memory */
	si_meminfo(&si);
	seq_printf(m, "mem_total_kb: %lu\n",
		   si.totalram * (si.mem_unit / 1024));
	seq_printf(m, "mem_free_kb:  %lu\n",
		   si.freeram  * (si.mem_unit / 1024));
	seq_printf(m, "mem_used_kb:  %lu\n",
		   (si.totalram - si.freeram) * (si.mem_unit / 1024));

	/* Uptime in seconds */
	seq_printf(m, "uptime_sec: %llu\n",
		   (unsigned long long)jiffies / HZ);

	return 0;
}

static int ai_metrics_open(struct inode *inode, struct file *file)
{
	return single_open(file, ai_metrics_show, NULL);
}

static const struct proc_ops ai_metrics_ops = {
	.proc_open    = ai_metrics_open,
	.proc_read    = seq_read,
	.proc_lseek   = seq_lseek,
	.proc_release = single_release,
};

/* ------------------------------------------------------------------ */
/* Module init / exit                                                  */
/* ------------------------------------------------------------------ */

static int __init ai_proc_init(void)
{
	struct proc_dir_entry *root = ai_get_proc_root();

	if (!root) {
		pr_err("ai_proc: ai_core not initialized\n");
		return -ENODEV;
	}

	if (!proc_create("metrics", 0444, root, &ai_metrics_ops)) {
		pr_err("ai_proc: failed to create /proc/ai/metrics\n");
		return -ENOMEM;
	}

	pr_info("ai_proc: /proc/ai/metrics registered\n");
	return 0;
}

static void __exit ai_proc_exit(void)
{
	struct proc_dir_entry *root = ai_get_proc_root();

	if (root)
		remove_proc_entry("metrics", root);

	pr_info("ai_proc: removed\n");
}

module_init(ai_proc_init);
module_exit(ai_proc_exit);

MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("AI /proc metrics interface");
