package com.gainandshine.unloop.core.limits

import com.gainandshine.unloop.core.store.Json

/**
 * Several apps sharing ONE daily time limit ("Social: 1 hour across Instagram, TikTok and X"). Usage of every member
 * is added up and compared with [dailySeconds]. Only the group-limit logic lives here; the shell stores the list.
 */
data class AppGroup(
    val id: String,
    val name: String,
    val pkgs: Set<String>,
    val dailySeconds: Long,
    val warnPercents: List<Int> = listOf(80),
) {
    init {
        require(id.isNotBlank()) { "group id" }
        require(dailySeconds >= 0) { "dailySeconds must be >= 0" }
    }

    /** The rule the shared quota is evaluated with. [target] only labels it. */
    fun rule() = LimitRule("group:$id", dailySeconds, warnPercents)
}

object AppGroups {
    /** Groups [pkg] belongs to. An app can be in several. */
    fun containing(pkg: String, groups: List<AppGroup>): List<AppGroup> = groups.filter { pkg in it.pkgs }

    /** Every package that belongs to at least one group (they must be watched and enforced). */
    fun members(groups: List<AppGroup>): Set<String> = groups.flatMapTo(LinkedHashSet()) { it.pkgs }

    /** Seconds used today by all members together. [millisFor] returns today's milliseconds for one package. */
    fun usedSeconds(group: AppGroup, millisFor: (String) -> Long): Long = group.pkgs.sumOf { millisFor(it) } / 1000

    fun parse(json: String?): List<AppGroup> = try {
        (Json.parse(json ?: "[]") as List<*>).mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            runCatching {
                AppGroup(
                    id = m["id"] as String,
                    name = (m["name"] as? String)?.takeIf { it.isNotBlank() } ?: "Group",
                    pkgs = (m["pkgs"] as? List<*>).orEmpty().filterIsInstance<String>().filter { it.isNotBlank() }.toCollection(LinkedHashSet()),
                    dailySeconds = (m["dailySeconds"] as Number).toLong(),
                    warnPercents = (m["warnPercents"] as? List<*>)?.filterIsInstance<Number>()?.map { it.toInt() } ?: listOf(80),
                )
            }.getOrNull()
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun write(groups: List<AppGroup>): String = Json.write(
        groups.map {
            linkedMapOf(
                "id" to it.id, "name" to it.name, "pkgs" to it.pkgs.toList(),
                "dailySeconds" to it.dailySeconds, "warnPercents" to it.warnPercents,
            )
        },
    )
}
