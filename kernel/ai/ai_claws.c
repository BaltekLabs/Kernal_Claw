// SPDX-License-Identifier: GPL-2.0
/*
 * AI Claws - system monitoring hooks
 *
 * Lightweight hooks into kernel subsystems to surface real-time
 * system state for the AI daemon via /proc/ai/claws.
 *
 * Bug fix: added #include <linux/sched/loadavg.h> to resolve
 * undefined references to avenrun[] and FSHIFT.
 */

#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/proc_fs.h>
#include <linux/seq_file.h>
#include <linux/sched.h>
#include <linux/sched/loadavg.h>   /* avenrun[], FSHIFT, LOAD_INT, LOAD_FRAC */
#include <linux/sched/stat.h>      /* nr_running() */
#include <linux/cpumask.h>
#include <linux/mm.h>

#include "ai.h"

/* ------------------------------------------------------------------ */
/* /proc/ai/claws                                                      */
/* ------------------------------------------------------------------ */

static int ai_claws_show(struct seq_file *m, void *v)
{
	unsigned long avnrun[3];

	/* Load averages — requires sched/loadavg.h (bug fix applied) */
	get_avenrun(avnrun, FIXED_1 / 200, 0);

	seq_puts(m, "# AI Claws System State\n");

	seq_printf(m, "load1:       %lu.%02lu\n",
		   LOAD_INT(avnrun[0]), LOAD_FRAC(avnrun[0]));
	seq_printf(m, "load5:       %lu.%02lu\n",
		   LOAD_INT(avnrun[1]), LOAD_FRAC(avnrun[1]));
	seq_printf(m, "load15:      %lu.%02lu\n",
		   LOAD_INT(avnrun[2]), LOAD_FRAC(avnrun[2]));

	seq_printf(m, "nr_online_cpus: %d\n", num_online_cpus());
	seq_printf(m, "nr_running:     %u\n",  nr_running());

	return 0;
}

static int ai_claws_open(struct inode *inode, struct file *file)
{
	return single_open(file, ai_claws_show, NULL);
}

static const struct proc_ops ai_claws_ops = {
	.proc_open    = ai_claws_open,
	.proc_read    = seq_read,
	.proc_lseek   = seq_lseek,
	.proc_release = single_release,
};

/* ------------------------------------------------------------------ */
/* Module init / exit                                                  */
/* ------------------------------------------------------------------ */

static int __init ai_claws_init(void)
{
	struct proc_dir_entry *root = ai_get_proc_root();

	if (!root) {
		pr_err("ai_claws: ai_core not initialized\n");
		return -ENODEV;
	}

	if (!proc_create("claws", 0444, root, &ai_claws_ops)) {
		pr_err("ai_claws: failed to create /proc/ai/claws\n");
		return -ENOMEM;
	}

	pr_info("ai_claws: system monitoring initialized\n");
	return 0;
}

static void __exit ai_claws_exit(void)
{
	struct proc_dir_entry *root = ai_get_proc_root();

	if (root)
		remove_proc_entry("claws", root);

	pr_info("ai_claws: removed\n");
}

module_init(ai_claws_init);
module_exit(ai_claws_exit);

MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("AI system monitoring claws");
