package com.mtajchert.carom.app.feature.tasks.api

import android.net.Uri
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.deeplink.DeepLinkMatcher
import androidx.navigation3.runtime.deeplink.DeepLinkRequest
import androidx.navigation3.runtime.deeplink.DeepLinkUri
import androidx.navigation3.runtime.deeplink.UriDeepLinkMatcher
import androidx.navigation3.runtime.deeplink.withBackStack

/**
 * Public deep-link contract for the Tasks feature.
 *
 * URI matchers deserialize arguments into the same serializable keys used by
 * NavDisplay. withBackStack keeps the list underneath create/detail so Back
 * returns to the list. Matching does not replace product argument validation.
 */
object TasksDeepLinks {
    const val WEB_SCHEME: String = "https"
    // Replace this host and publish /.well-known/assetlinks.json before shipping.
    const val WEB_HOST: String = "tajchert.github.io"
    // Custom schemes cannot be verified and can be claimed by other apps.
    const val APP_SCHEME: String = "carom"

    private val taskIdPattern = Regex("[A-Za-z0-9_-]{1,64}")

    // Keep URI declarations in the feature API alongside its navigation keys.
    // Explicit schemes avoid the implicit http/https matching of schemeless patterns.
    private val matchers = listOf(
        "$WEB_SCHEME://$WEB_HOST/tasks",
        "$APP_SCHEME://tasks",
    ).flatMap { root ->
        listOf(
            UriDeepLinkMatcher(DeepLinkUri(root), TaskListRoute.serializer())
                .withBackStack { listOf<NavKey>(it.key) },
            UriDeepLinkMatcher(
                DeepLinkUri("$root/new"),
                CreateTaskRoute.serializer(),
                // The library matches URI patterns without regard to case.
                // Our literal `new` is case-sensitive; NEW remains a task ID.
                filters = listOf(DeepLinkMatcher.Filter { it.uri?.lastPathSegment == "new" }),
            )
                .withBackStack { listOf<NavKey>(TaskListRoute, it.key) },
            UriDeepLinkMatcher(DeepLinkUri("$root/{taskId}"), TaskDetailRoute.serializer())
                .withBackStack { listOf<NavKey>(TaskListRoute, it.key) },
        )
    }

    /** Returns a validated synthetic stack, or null for an unrecognized URI. */
    fun resolve(uri: Uri?): List<NavKey>? {
        if (uri == null) return null
        // URI matcher regexes ignore path case. Enforce the feature's literal
        // web root independently while leaving task identifiers untouched.
        if (uri.scheme.equals(WEB_SCHEME, ignoreCase = true) &&
            uri.pathSegments.firstOrNull() != "tasks"
        ) return null
        // Android's URI scheme is case-sensitive; the public contract accepts
        // scheme/host case variants. Do not lowercase paths or identifiers.
        // Canonicalize each segment separately: %6Eew and new must select the
        // same literal route. Re-encoding preserves escaped separators and
        // prevents double decoding; splitting the encoded path retains empties.
        val canonicalPath = uri.encodedPath?.split('/')
            ?.joinToString("/") { Uri.encode(Uri.decode(it)) }
        val normalizedUri = uri.normalizeScheme().buildUpon()
            .encodedPath(canonicalPath).build()
        val request = DeepLinkRequest(uri = normalizedUri)
        val match = matchers.mapNotNull { it.match(request) }.maxOrNull() ?: return null
        // Best-match ranking makes the literal /new route win over /{taskId}.
        val key = match.key
        if (key is TaskDetailRoute && !taskIdPattern.matches(key.taskId)) return null
        return match.backStack
    }
}
