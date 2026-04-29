package com.ace.crm.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.ace.crm.data.ContactEntity
import java.io.File

object VcfExporter {
    fun exportAndShare(
        context: Context,
        contacts: List<ContactEntity>,
        authority: String
    ): Boolean {
        if (contacts.isEmpty()) return false

        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val vcfFile = File(exportDir, "contacts.vcf")

        vcfFile.writeText(buildVcf(contacts))

        val uri = FileProvider.getUriForFile(context, authority, vcfFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/x-vcard"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "Share contacts"))
        return true
    }

    private fun buildVcf(contacts: List<ContactEntity>): String {
        return buildString {
            contacts.forEach { contact ->
                appendLine("BEGIN:VCARD")
                appendLine("VERSION:3.0")
                appendLine("FN:${sanitize(contact.displayName)}")
                contact.phoneNumber?.takeIf { it.isNotBlank() }?.let {
                    appendLine("TEL;TYPE=CELL:${sanitize(it)}")
                }
                contact.email?.takeIf { it.isNotBlank() }?.let {
                    appendLine("EMAIL:${sanitize(it)}")
                }
                appendLine("END:VCARD")
            }
        }
    }

    private fun sanitize(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\n", "\\n")
    }
}
