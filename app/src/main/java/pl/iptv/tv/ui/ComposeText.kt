package pl.iptv.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

/**
 * (7.8) Tekst Compose w wybranym języku ([I18n]) – ta sama nazwa i parametry co `androidx.compose.foundation.text.BasicText`,
 * więc ekrany Compose (Nowoczesny, PS5, X25, biblioteka aplikacji) używają jej bez zmian w wywołaniach. Przy języku polskim
 * tekst przechodzi bez zmian.
 */
@Composable
fun BasicText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
) = androidx.compose.foundation.text.BasicText(I18n.ts(text), modifier, style, onTextLayout, overflow, softWrap, maxLines, minLines)
