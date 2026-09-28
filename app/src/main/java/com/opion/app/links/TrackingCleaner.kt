package com.opion.app.links

import android.net.Uri
import java.util.Locale

data class CleanResult(
    val original: String,
    val cleaned: String,
    val removed: List<String>
) {
    val changed: Boolean get() = cleaned != original
}

data class TextCleanResult(
    val originalText: String,
    val cleanedText: String,
    val cleanedUrls: List<String>,
    val removedParams: List<String>
) {
    val changed: Boolean get() = cleanedText != originalText
}

object TrackingCleaner {

    private val TRACKING_PARAMS: Set<String> = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "utm_id", "utm_name", "utm_reader", "utm_brand", "utm_social",
        "utm_social-type", "utm_source_platform", "utm_creative_format",
        "utm_marketing_tactic", "utm_referrer",
        "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "gad_source",
        "gad_campaignid", "_gl", "gali", "srsltid",
        "fbclid", "_fbp", "_fbc", "fb_action_ids", "fb_action_types",
        "fb_source", "fb_ref", "igsh", "igshid", "img_index", "ig_mid",
        "ttclid", "tt_medium", "tt_content",
        "twclid", "ref_src", "ref_url", "__twitter_impression",
        "msclkid", "yclid", "_openstat",
        "si",
        "mc_cid", "mc_eid", "mkt_tok", "vero_id", "vero_conv", "wickedid",
        "oly_anon_id", "oly_enc_id", "rb_clickid", "s_cid", "epik", "trk",
        "trkcampaign", "icid", "_hsenc", "_hsmi", "hsa_cam", "hsa_grp",
        "hsa_mt", "hsa_src", "hsa_ad", "hsa_acc", "hsa_net", "hsa_kw",
        "piwik_campaign", "pk_campaign", "pk_kwd", "mtm_campaign",
        "mtm_source", "mtm_medium", "mtm_content", "mtm_keyword", "mtm_cid",
        "mtm_group", "mtm_placement", "at_medium", "at_campaign"
    )

    private val FRAGMENT_TRACKING: Set<String> = setOf(
        "tracking_id", "position", "search_layout", "pdp_filters", "quantity",
        "variation", "thumbnail", "c_id", "c_uid", "reco_backend",
        "reco_client", "reco_item_pos", "reco_backend_type", "source",
        "from", "sid", "wid", "is_advertising", "ad_domain", "ad_position"
    )

    private val UNWRAP_KEYS = listOf("u", "url", "q", "target", "to", "link", "redirect", "href")

    private data class Redirector(val host: String, val paths: Set<String>?)
    private val REDIRECTORS: List<Redirector> = listOf(
        Redirector("www.google.com", setOf("/url")),
        Redirector("google.com", setOf("/url")),
        Redirector("www.youtube.com", setOf("/redirect")),
        Redirector("youtube.com", setOf("/redirect")),
        Redirector("www.facebook.com", setOf("/l.php")),
        Redirector("m.facebook.com", setOf("/l.php")),
        Redirector("l.facebook.com", null),
        Redirector("lm.facebook.com", null),
        Redirector("l.instagram.com", null),
        Redirector("l.threads.net", null),
        Redirector("out.reddit.com", null),
        Redirector("away.vk.com", null),
        Redirector("redirect.spotify.com", null)
    )

    private val URL_REGEX = Regex("""(?i)https?://[^\s<>"'`]+""")
    private val WWW_REGEX = Regex("""(?i)\bwww\.[^\s<>"'`]+""")
    private val TRAILING_JUNK = charArrayOf(
        '.', ',', ';', ':', '!', ')', ']', '}', '"', '\'', '»', '”', '…'
    )

    fun clean(rawInput: String): CleanResult {
        val input = rawInput.trim()
        if (input.isEmpty()) return CleanResult(rawInput, rawInput, emptyList())

        val hadScheme = input.contains("://")
        var working = if (hadScheme) input else "https://$input"

        var hops = 0
        while (hops < 3) {
            val unwrapped = tryUnwrap(working) ?: break
            working = unwrapped
            hops++
        }

        var base = working
        var fragment: String? = null
        val hashIndex = base.indexOf('#')
        if (hashIndex >= 0) {
            fragment = base.substring(hashIndex + 1)
            base = base.substring(0, hashIndex)
        }

        val queryIndex = base.indexOf('?')
        val head = if (queryIndex >= 0) base.substring(0, queryIndex) else base
        val query = if (queryIndex >= 0) base.substring(queryIndex + 1) else null

        val removed = LinkedHashSet<String>()

        val keptPairs = ArrayList<String>()
        if (query != null) {
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val key = pair.substringBefore('=').lowercase(Locale.US)
                if (key.startsWith("utm_") || key in TRACKING_PARAMS) {
                    removed.add(key)
                } else {
                    keptPairs.add(pair)
                }
            }
        }

        var newFragment = fragment
        if (fragment != null && fragment.contains('=')) {
            val pairs = fragment.split('&').filter { it.isNotEmpty() }
            val keys = pairs.map { it.substringBefore('=').lowercase(Locale.US) }
            if (keys.isNotEmpty() && keys.all { it in FRAGMENT_TRACKING }) {
                pairs.forEach { removed.add("fragmento:" + it.substringBefore('=')) }
                newFragment = null
            }
        }

        val sb = StringBuilder(head)
        if (keptPairs.isNotEmpty()) {
            sb.append('?').append(keptPairs.joinToString("&"))
        }
        if (!newFragment.isNullOrEmpty()) {
            sb.append('#').append(newFragment)
        }

        var cleaned = sb.toString()
        if (!hadScheme) cleaned = cleaned.removePrefix("https://")

        // Si quedó un "?" colgando sin parámetros, lo sacamos
        if (cleaned.endsWith("?") || cleaned.endsWith("#")) {
            cleaned = cleaned.dropLast(1)
        }

        return CleanResult(rawInput, cleaned, removed.toList())
    }

    fun cleanAll(text: String): TextCleanResult {
        val urls = findAllUrls(text)
        if (urls.isEmpty()) {
            return TextCleanResult(text, text, emptyList(), emptyList())
        }

        var out = text
        val cleanedUrls = ArrayList<String>(urls.size)
        val removedTotal = LinkedHashSet<String>()

        for (url in urls) {
            val result = clean(url)
            cleanedUrls.add(result.cleaned)
            removedTotal.addAll(result.removed)
            if (result.changed) out = out.replace(url, result.cleaned)
        }

        return TextCleanResult(text, out, cleanedUrls, removedTotal.toList())
    }

    private fun tryUnwrap(url: String): String? {
        val uri = try {
            Uri.parse(url)
        } catch (_: Throwable) {
            return null
        }
        val host = uri.host?.lowercase(Locale.US) ?: return null
        val path = uri.path?.lowercase(Locale.US) ?: ""

        val redirector = REDIRECTORS.firstOrNull { it.host == host } ?: return null
        if (redirector.paths != null && path !in redirector.paths) return null

        for (key in UNWRAP_KEYS) {
            val value = try {
                uri.getQueryParameter(key)
            } catch (_: Throwable) {
                null
            } ?: continue

            val decoded = try {
                Uri.decode(value)
            } catch (_: Throwable) {
                value
            }
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                return decoded
            }
        }
        return null
    }

    private fun findAllUrls(text: String): List<String> {
        val found = LinkedHashMap<String, IntRange>()

        for (m in URL_REGEX.findAll(text)) {
            found[trimJunk(m.value)] = m.range
        }
        for (m in WWW_REGEX.findAll(text)) {
            val already = found.values.any { m.range.first >= it.first && m.range.last <= it.last }
            if (!already) found[trimJunk(m.value)] = m.range
        }

        return found.keys.filter { it.isNotBlank() }
    }

    private fun trimJunk(url: String): String =
        url.trimEnd(*TRAILING_JUNK)
}
