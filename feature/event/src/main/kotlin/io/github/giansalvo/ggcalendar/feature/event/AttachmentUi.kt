package io.github.giansalvo.ggcalendar.feature.event

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.giansalvo.ggcalendar.core.model.AttachmentLink
import io.github.giansalvo.ggcalendar.core.model.EventAttachment
import io.github.giansalvo.ggcalendar.core.notifications.exposeTestTags

/** The kind of file shown in the small badge of a row: from the type Google or the extension gave, else a plain link. */
internal enum class AttachmentKind(val label: String, val color: Color, val dark: Boolean = false) {
    PDF("PDF", Color(0xFFD93025)),
    IMAGE("IMG", Color(0xFF1E8E3E)),
    DOCUMENT("DOC", Color(0xFF1A73E8)),
    SHEET("XLS", Color(0xFF188038)),
    DRIVE("▲", Color(0xFFF9AB00), dark = true),
    LINK("LINK", Color(0xFF6B6572)),
}

internal fun attachmentKind(a: EventAttachment): AttachmentKind {
    val type = a.mimeType.orEmpty()
    return when {
        type == "application/pdf" -> AttachmentKind.PDF
        type.startsWith("image/") -> AttachmentKind.IMAGE
        type.contains("word") || type == "application/msword" || type.contains("presentation") || type.contains("powerpoint") || type.contains("opendocument.text") -> AttachmentKind.DOCUMENT
        type.contains("sheet") || type.contains("excel") || type == "text/csv" -> AttachmentKind.SHEET
        a.isDrive -> AttachmentKind.DRIVE
        else -> AttachmentKind.LINK
    }
}

@Composable
internal fun AttachmentBadge(a: EventAttachment, modifier: Modifier = Modifier) {
    val kind = attachmentKind(a)
    val shape = RoundedCornerShape(7.dp)
    Box(modifier.size(width = 36.dp, height = 28.dp).background(if (a.canOpen) kind.color else MaterialTheme.colorScheme.outline, shape), contentAlignment = Alignment.Center) {
        Text(kind.label, color = if (kind.dark) Color(0xFF222222) else Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/**
 * The question before a link is opened (mockup 2): the real site and the whole address, because the title can say anything. "Open" starts
 * an explicit VIEW intent; Cancel and Back open nothing.
 */
@Composable
internal fun OpenAttachmentDialog(attachment: EventAttachment, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        modifier = Modifier.exposeTestTags(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.attachment_open_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(stringResource(R.string.attachment_open_goes_to))
                Text(AttachmentLink.host(attachment.url).orEmpty(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("attachment_open_site").padding(top = 6.dp))
                Text(attachment.url, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("attachment_open_url").padding(top = 6.dp))
                Text(stringResource(R.string.attachment_open_trust), modifier = Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.testTag("attachment_open_confirm"), onClick = {
                onDismiss()
                openAttachment(context, attachment.url)
            }) { Text(stringResource(R.string.attachment_open)) }
        },
        dismissButton = { TextButton(modifier = Modifier.testTag("attachment_open_cancel"), onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Starts the browser (or the app that handles the address, such as Drive) on [url]; nothing happens when no app can. */
internal fun openAttachment(context: Context, url: String) {
    if (!AttachmentLink.isValid(url)) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).addCategory(Intent.CATEGORY_BROWSABLE)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}
