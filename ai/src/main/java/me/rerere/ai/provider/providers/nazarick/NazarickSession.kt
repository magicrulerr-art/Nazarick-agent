package me.rerere.ai.provider.providers.nazarick

/**
 * Session-id namespacing for app-originated conversations (US-003).
 *
 * The server treats `session_id` as caller-chosen and ships a plain `default`; the app
 * must never collide with it, so every app session is `app-<deviceSlug>-<chatId>` with
 * both parts sanitized into safe slug characters.
 */
fun appSessionId(deviceSlug: String, chatId: String): String {
    val parts = listOf(sanitizeSessionPart(deviceSlug), sanitizeSessionPart(chatId))
        .filter { it.isNotEmpty() }
    return "app-" + parts.joinToString("-")
}

/**
 * Slug sanitizer: lowercases, keeps only `[a-z0-9-]` in the output alphabet, maps any
 * run of disallowed characters (punctuation, spaces, `_`, unicode) to a single `-`,
 * collapses repeated separators, and trims separators from both edges.
 *
 * Deterministic and idempotent: `sanitizeSessionPart(sanitizeSessionPart(x)) ==
 * sanitizeSessionPart(x)`, so a slug re-sanitized on a later request never drifts.
 */
fun sanitizeSessionPart(raw: String): String {
    val out = StringBuilder(raw.length)
    var lastWasSeparator = false
    for (ch in raw.lowercase()) {
        if (ch in 'a'..'z' || ch in '0'..'9') {
            out.append(ch)
            lastWasSeparator = false
        } else if (!lastWasSeparator) {
            out.append('-')
            lastWasSeparator = true
        }
    }
    return out.toString().trim('-')
}
