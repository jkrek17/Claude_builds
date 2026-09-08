package com.survivor.engine

enum class Conference { AFC, NFC }

enum class Division(val conference: Conference, val label: String) {
    AFC_EAST(Conference.AFC, "AFC East"), AFC_NORTH(Conference.AFC, "AFC North"),
    AFC_SOUTH(Conference.AFC, "AFC South"), AFC_WEST(Conference.AFC, "AFC West"),
    NFC_EAST(Conference.NFC, "NFC East"), NFC_NORTH(Conference.NFC, "NFC North"),
    NFC_SOUTH(Conference.NFC, "NFC South"), NFC_WEST(Conference.NFC, "NFC West"),
}

/**
 * All 32 NFL teams keyed by the abbreviation ESPN uses in its public JSON (e.g. "WSH", "LAR").
 * [tzOffsetHours] is the home market's standard UTC offset, used only for the travel proxy.
 * [fullName] must match the display names The Odds API uses so its feed can be joined to ESPN games.
 */
enum class Team(val abbr: String, val city: String, val nickname: String, val division: Division, val tzOffsetHours: Int) {
    BUF("BUF", "Buffalo", "Bills", Division.AFC_EAST, -5),
    MIA("MIA", "Miami", "Dolphins", Division.AFC_EAST, -5),
    NE("NE", "New England", "Patriots", Division.AFC_EAST, -5),
    NYJ("NYJ", "New York", "Jets", Division.AFC_EAST, -5),
    BAL("BAL", "Baltimore", "Ravens", Division.AFC_NORTH, -5),
    CIN("CIN", "Cincinnati", "Bengals", Division.AFC_NORTH, -5),
    CLE("CLE", "Cleveland", "Browns", Division.AFC_NORTH, -5),
    PIT("PIT", "Pittsburgh", "Steelers", Division.AFC_NORTH, -5),
    HOU("HOU", "Houston", "Texans", Division.AFC_SOUTH, -6),
    IND("IND", "Indianapolis", "Colts", Division.AFC_SOUTH, -5),
    JAX("JAX", "Jacksonville", "Jaguars", Division.AFC_SOUTH, -5),
    TEN("TEN", "Tennessee", "Titans", Division.AFC_SOUTH, -6),
    DEN("DEN", "Denver", "Broncos", Division.AFC_WEST, -7),
    KC("KC", "Kansas City", "Chiefs", Division.AFC_WEST, -6),
    LV("LV", "Las Vegas", "Raiders", Division.AFC_WEST, -8),
    LAC("LAC", "Los Angeles", "Chargers", Division.AFC_WEST, -8),
    DAL("DAL", "Dallas", "Cowboys", Division.NFC_EAST, -6),
    NYG("NYG", "New York", "Giants", Division.NFC_EAST, -5),
    PHI("PHI", "Philadelphia", "Eagles", Division.NFC_EAST, -5),
    WSH("WSH", "Washington", "Commanders", Division.NFC_EAST, -5),
    CHI("CHI", "Chicago", "Bears", Division.NFC_NORTH, -6),
    DET("DET", "Detroit", "Lions", Division.NFC_NORTH, -5),
    GB("GB", "Green Bay", "Packers", Division.NFC_NORTH, -6),
    MIN("MIN", "Minnesota", "Vikings", Division.NFC_NORTH, -6),
    ATL("ATL", "Atlanta", "Falcons", Division.NFC_SOUTH, -5),
    CAR("CAR", "Carolina", "Panthers", Division.NFC_SOUTH, -5),
    NO("NO", "New Orleans", "Saints", Division.NFC_SOUTH, -6),
    TB("TB", "Tampa Bay", "Buccaneers", Division.NFC_SOUTH, -5),
    ARI("ARI", "Arizona", "Cardinals", Division.NFC_WEST, -7),
    LAR("LAR", "Los Angeles", "Rams", Division.NFC_WEST, -8),
    SF("SF", "San Francisco", "49ers", Division.NFC_WEST, -8),
    SEA("SEA", "Seattle", "Seahawks", Division.NFC_WEST, -8);

    val fullName: String get() = "$city $nickname"

    companion object {
        private val byAbbr: Map<String, Team> = entries.associateBy { it.abbr }
        private val aliases = mapOf("WAS" to WSH, "LA" to LAR, "OAK" to LV, "SD" to LAC, "STL" to LAR, "JAC" to JAX)
        private val byFullName: Map<String, Team> = entries.associateBy { it.fullName.lowercase() }

        fun fromAbbr(abbr: String): Team? = byAbbr[abbr.uppercase()] ?: aliases[abbr.uppercase()]
        fun fromFullName(name: String): Team? = byFullName[name.trim().lowercase()]
            ?: entries.firstOrNull { name.trim().endsWith(it.nickname, ignoreCase = true) }

        const val COUNT = 32
    }
}
