package io.github.immaghzbad.aetherst.shared.data

import io.github.immaghzbad.aetherst.platform.SystemUtils
import io.github.immaghzbad.aetherst.platform.isDesktop
import io.github.immaghzbad.aetherst.shared.model.AetherConfig
import io.github.immaghzbad.aetherst.shared.model.RoutingMode
import io.github.immaghzbad.aetherst.shared.model.RoutingRule
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Loads the bundled "Iran direct" preset and merges it with the user's routing rules.
 *
 * The preset is applied on desktop only. On Android the core already receives these rules
 * through the user-triggered "Iran Direct" import button, so the default behaviour there
 * must not change.
 */
object IranDirectPreset {

    const val ASSET_NAME = "iran-direct-domains-ipv4-ipv6.astb"

    private val cache = AtomicReference<List<RoutingRule>?>(null)

    /** Parses the bundled .astb file (two lines per rule: `pattern` then `-mode`). */
    fun rules(systemUtils: SystemUtils): List<RoutingRule> {
        cache.get()?.let { return it }
        val parsed = runCatching { parse(systemUtils.readInternalAsset(ASSET_NAME)) }
            .getOrDefault(emptyList())
        cache.compareAndSet(null, parsed)
        return parsed
    }

    fun count(systemUtils: SystemUtils): Int = rules(systemUtils).count { it.mode == RoutingMode.DIRECT }

    /**
     * Screens in commonMain have no SystemUtils handle, so the platform installs a cheap
     * accessor here once (desktop only; Android never uses the preset implicitly).
     */
    @Volatile
    private var countProvider: (() -> Int)? = null

    fun installCountProvider(provider: () -> Int) {
        countProvider = provider
    }

    fun cachedCount(): Int = countProvider?.invoke() ?: 0

    /**
     * User rules first (they always win), then the preset. A preset pattern is skipped when
     * the user already configured that exact pattern with any mode (so an explicit
     * TUNNEL/BLOCK of the user is never overridden by the preset).
     */
    fun effectiveRules(config: AetherConfig, systemUtils: SystemUtils): List<RoutingRule> {
        val userRules = config.routingRules
        if (!isDesktop || !config.iranDirectEnabled) return userRules
        val known = userRules.mapTo(HashSet()) { it.pattern.trim().lowercase(Locale.ROOT) }
        val merged = ArrayList<RoutingRule>(userRules.size + 320)
        merged.addAll(userRules)
        for (rule in rules(systemUtils)) {
            if (rule.mode != RoutingMode.DIRECT) continue
            if (known.add(rule.pattern.trim().lowercase(Locale.ROOT))) merged.add(rule)
        }
        return merged
    }

    private fun parse(text: String?): List<RoutingRule> {
        if (text.isNullOrBlank()) return emptyList()
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<RoutingRule>(lines.size / 2 + 1)
        var index = 0
        while (index + 1 < lines.size) {
            val pattern = lines[index]
            val modeToken = lines[index + 1].removePrefix("-").uppercase(Locale.ROOT)
            val mode = when (modeToken) {
                "DIRECT" -> RoutingMode.DIRECT
                "BLOCK" -> RoutingMode.BLOCK
                "TUNNEL" -> RoutingMode.TUNNEL
                else -> null
            }
            val isKnownPrefix = pattern.startsWith("domain:") || pattern.startsWith("ip:") ||
                pattern.startsWith("keyword:") || pattern.startsWith("regexp:")
            if (mode != null && isKnownPrefix) {
                out.add(RoutingRule(pattern, mode))
            }
            index += 2
        }
        return out
    }
}
