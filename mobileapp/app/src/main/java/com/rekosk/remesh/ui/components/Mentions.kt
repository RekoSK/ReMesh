package com.rekosk.remesh.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

// ------------------------------------------------------------------ wire format

/** A tag on the wire is `@[name]`; only the name is shown, inside a chip. */
val MentionRegex = Regex("""@\[([^\[\]\n]+)\]""")

/** True when [text] tags [name] via a `@[name]` token (trimmed, case-insensitive). */
fun mentionsName(text: String, name: String?): Boolean {
    if (name.isNullOrBlank()) return false
    val target = name.trim()
    return MentionRegex.findAll(text).any { it.groupValues[1].trim().equals(target, ignoreCase = true) }
}

// -------------------------------------------------------------- typing helpers
// Pure (no Compose) so they are unit-testable on plain strings.

/**
 * The `@query` being typed at [cursor], if any: a bare `@` at a word boundary with no
 * space/brackets between it and the cursor (i.e. not an already-completed `@[..]` token).
 * Returns the `@`'s index and the text typed after it.
 */
fun findActiveMention(text: String, cursor: Int): Pair<Int, String>? {
    if (cursor <= 0 || cursor > text.length) return null
    var i = cursor - 1
    while (i >= 0) {
        when (text[i]) {
            '@' -> {
                val boundaryOk = i == 0 || text[i - 1] == ' ' || text[i - 1] == '\n'
                if (!boundaryOk) return null
                return i to text.substring(i + 1, cursor)
            }
            ' ', '\n', '[', ']' -> return null
            else -> i--
        }
    }
    return null
}

/** Replaces the range `[atIndex, replaceEnd)` with a completed `@[name] ` token. */
fun insertMention(text: String, atIndex: Int, replaceEnd: Int, name: String): Pair<String, Int> {
    val token = "@[$name] "
    val newText = text.substring(0, atIndex) + token + text.substring(replaceEnd.coerceIn(atIndex, text.length))
    return newText to (atIndex + token.length)
}

data class NormalizedText(val text: String, val cursor: Int)

/**
 * Applies the two implicit-tagging rules after a keystroke:
 *  - **space-confirm**: typing a space right after a bare `@word` wraps it to `@[word] `.
 *  - **backspace-revert**: deleting the closing `]` of a `@[word]` token unwraps it back to
 *    plain `word` (so backspacing cancels the tag).
 * Idempotent, and only touches the token next to the cursor.
 */
fun normalizeMentionTyping(text: String, cursor: Int): NormalizedText {
    // space-confirm: "…@word " → "…@[word] "
    if (cursor in 1..text.length && text[cursor - 1] == ' ') {
        var i = cursor - 2
        while (i >= 0 && text[i] !in charArrayOf(' ', '\n', '@', '[', ']')) i--
        if (i >= 0 && text[i] == '@') {
            val word = text.substring(i + 1, cursor - 1)
            val boundaryOk = i == 0 || text[i - 1] == ' ' || text[i - 1] == '\n'
            if (word.isNotEmpty() && boundaryOk) {
                val newText = text.substring(0, i) + "@[" + word + "] " + text.substring(cursor)
                return NormalizedText(newText, cursor + 2)
            }
        }
    }
    // backspace-revert: a malformed "@[word" (its ] was deleted) → "word"
    val open = text.lastIndexOf("@[", (cursor - 1).coerceAtLeast(0))
    if (open >= 0) {
        val tokenEnd = text.indexOf(' ', open + 2).let { if (it == -1) text.length else it }
        val close = text.indexOf(']', open + 2)
        val malformed = close == -1 || close >= tokenEnd
        val boundaryOk = open == 0 || text[open - 1] == ' ' || text[open - 1] == '\n'
        if (malformed && boundaryOk) {
            val newText = text.removeRange(open, open + 2)
            val newCursor = (if (cursor > open + 1) cursor - 2 else cursor).coerceIn(0, newText.length)
            return NormalizedText(newText, newCursor)
        }
    }
    return NormalizedText(text, cursor)
}

// ------------------------------------------------------------------ appearance

/** Black or white, whichever reads better on [bg]. */
fun onColorFor(bg: Color): Color = if (bg.luminance() > 0.5f) Color.Black else Color.White

/**
 * A rounded-rect outline whose straight edges (and corners) ripple like the app's wavy
 * "snake" progress bar. [phase] animates the ripple; [amplitude] is the wave depth.
 */
class MentionShape(
    private val cornerRadius: Dp,
    private val amplitude: Dp = 1.5.dp,
    private val waveLength: Dp = 8.dp,
    private val phase: Float = 0f,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val amp = with(density) { amplitude.toPx() }
        val corner = with(density) { cornerRadius.toPx() }
        val wl = with(density) { waveLength.toPx() }
        return Outline.Generic(wavyRoundRectPath(size.width, size.height, corner, amp, wl, phase))
    }
}

/** Builds the wavy rounded-rect path used by [MentionShape] and the tagged-me bubble border. */
fun wavyRoundRectPath(
    w: Float,
    h: Float,
    cornerRadius: Float,
    amplitude: Float,
    waveLength: Float,
    phase: Float,
): Path {
    val path = Path()
    val left = amplitude
    val top = amplitude
    val right = w - amplitude
    val bottom = h - amplitude
    if (right <= left || bottom <= top) {
        path.addRect(Rect(0f, 0f, w, h))
        return path
    }
    val r = cornerRadius.coerceIn(0f, minOf(right - left, bottom - top) / 2f)
    val wl = waveLength.coerceAtLeast(1f)
    val k = 2.0 * PI / wl
    var s = 0.0
    var started = false
    fun add(px: Float, py: Float) {
        if (!started) {
            path.moveTo(px, py)
            started = true
        } else {
            path.lineTo(px, py)
        }
    }
    fun straight(x0: Float, y0: Float, x1: Float, y1: Float, nx: Float, ny: Float) {
        val len = hypot(x1 - x0, y1 - y0)
        val steps = max(2, (len / 2f).toInt())
        for (i in 0..steps) {
            val f = i.toFloat() / steps
            val off = (amplitude * sin(k * s + phase)).toFloat()
            add(x0 + (x1 - x0) * f + nx * off, y0 + (y1 - y0) * f + ny * off)
            if (i < steps) s += len / steps
        }
    }
    fun arc(cx: Float, cy: Float, a0: Float, a1: Float) {
        val arcLen = abs(a1 - a0) * r
        val steps = max(2, (arcLen / 2f).toInt())
        for (i in 0..steps) {
            val a = a0 + (a1 - a0) * (i.toFloat() / steps)
            val nx = cos(a)
            val ny = sin(a)
            val off = (amplitude * sin(k * s + phase)).toFloat()
            add(cx + nx * (r + off), cy + ny * (r + off))
            if (i < steps) s += arcLen / steps
        }
    }
    straight(left + r, top, right - r, top, 0f, -1f)
    arc(right - r, top + r, (-PI / 2).toFloat(), 0f)
    straight(right, top + r, right, bottom - r, 1f, 0f)
    arc(right - r, bottom - r, 0f, (PI / 2).toFloat())
    straight(right - r, bottom, left + r, bottom, 0f, 1f)
    arc(left + r, bottom - r, (PI / 2).toFloat(), PI.toFloat())
    straight(left, bottom - r, left, top + r, -1f, 0f)
    arc(left + r, top + r, PI.toFloat(), (3 * PI / 2).toFloat())
    path.close()
    return path
}

/** A slowly travelling wave phase for the "snake" animation; 0 when [animated] is false. */
@Composable
fun rememberWavePhase(animated: Boolean): Float {
    if (!animated) return 0f
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "wavePhase",
    )
    return phase
}

/** The chip that stands in for a `@[name]` token: the name on a wavy pill in [color]. */
@Composable
fun MentionChip(name: String, color: Color, textStyle: TextStyle) {
    val shape = MentionShape(cornerRadius = 40.dp, amplitude = 1.5.dp, waveLength = 8.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name,
            style = textStyle,
            color = onColorFor(color),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

/**
 * Renders [text] with `@[name]` tokens replaced by inline [MentionChip]s coloured per
 * [colorForName]; everything else is drawn as ordinary text in [color].
 */
@Composable
fun MentionText(
    text: String,
    style: TextStyle,
    color: Color,
    colorForName: (String) -> Color,
    modifier: Modifier = Modifier,
) {
    val matches = MentionRegex.findAll(text).toList()
    if (matches.isEmpty()) {
        Text(text = text, style = style, color = color, modifier = modifier)
        return
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val chipStyle = style.merge(TextStyle(fontWeight = FontWeight.Medium))
    val hPadPx = with(density) { 8.dp.toPx() }
    val vPadPx = with(density) { 3.dp.toPx() }
    val inline = LinkedHashMap<String, InlineTextContent>()
    val annotated = buildAnnotatedString {
        var last = 0
        matches.forEachIndexed { idx, m ->
            append(text.substring(last, m.range.first))
            val name = m.groupValues[1]
            val id = "mention_$idx"
            val measured = measurer.measure(name, chipStyle)
            appendInlineContent(id, name)
            inline[id] = InlineTextContent(
                Placeholder(
                    width = with(density) { (measured.size.width + hPadPx * 2).toSp() },
                    height = with(density) { (measured.size.height + vPadPx * 2).toSp() },
                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                ),
            ) {
                MentionChip(name = name, color = colorForName(name), textStyle = chipStyle)
            }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
    Text(text = annotated, style = style, color = color, inlineContent = inline, modifier = modifier)
}

/** Amber accent for a bubble that tags you, with a dark edge — "yellow-black" outline. */
val MentionOutlineAmber = Color(0xFFFFC107)
val MentionOutlineDark = Color(0xFF241A00)

/**
 * Shows each `@[name]` token in the input as just `name` on a [colorForName]-tinted
 * highlight (the `@[` and `]` are hidden), while the underlying edited/sent text keeps the
 * full `@[name]` token. Compose editors can't host real shaped chips, so this is a flat
 * rounded-less highlight — the true wavy chip appears once the message is displayed.
 */
class MentionVisualTransformation(
    private val colorForName: (String) -> Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val src = text.text
        val matches = MentionRegex.findAll(src).toList()
        if (matches.isEmpty()) return TransformedText(text, OffsetMapping.Identity)

        val builder = AnnotatedString.Builder()
        val origToTrans = IntArray(src.length + 1)
        val transToOrig = ArrayList<Int>()
        var ti = 0
        var i = 0
        var mi = 0

        fun emit(ch: Char, origIndex: Int) {
            transToOrig.add(origIndex)
            builder.append(ch)
            ti++
        }

        while (i < src.length) {
            val m = matches.getOrNull(mi)
            if (m != null && i == m.range.first) {
                val name = m.groupValues[1]
                val start = m.range.first
                val endInclusive = m.range.last // index of ']'
                val base = colorForName(name)
                origToTrans[start] = ti
                origToTrans[start + 1] = ti
                builder.pushStyle(SpanStyle(background = base.copy(alpha = 0.30f), color = onColorFor(base)))
                for (k in name.indices) {
                    origToTrans[start + 2 + k] = ti
                    emit(name[k], start + 2 + k)
                }
                builder.pop()
                origToTrans[endInclusive] = ti
                i = endInclusive + 1
                mi++
            } else {
                origToTrans[i] = ti
                emit(src[i], i)
                i++
            }
        }
        origToTrans[src.length] = ti
        transToOrig.add(src.length)

        val transformed = builder.toAnnotatedString()
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                origToTrans[offset.coerceIn(0, src.length)].coerceIn(0, transformed.length)

            override fun transformedToOriginal(offset: Int): Int =
                transToOrig.getOrElse(offset.coerceIn(0, transformed.length)) { src.length }
                    .coerceIn(0, src.length)
        }
        return TransformedText(transformed, mapping)
    }
}
