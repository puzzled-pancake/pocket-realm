// GENERATED FILE - from schemas/timeframes/<flavor>.json by
// tools/generate_timeframe_era.py. Regenerate with:
//   python tools/generate_timeframe_era.py era
// schemas/timeframes/generated-era-classes-pin.json binds this file's
// sha256 to the source manifests; tools/check_timeframes.py fails loud on
// drift. The per-stage era classes follow design section 5: the world
// resolves its class set from PocketRealm.TimeframeStage; every stage
// whose manifest carries no curated delta resolves to its flavor's
// grounded baseline (W3.1: vanilla, tbc and wotlk each carry their own
// grounded baseline - three distinct per-flavor sets). DO NOT EDIT.

#ifndef _PlayerbotLlmEraStages_generated_h
#define _PlayerbotLlmEraStages_generated_h

#include <string>
#include <utility>
#include <vector>

namespace pocketllm {
namespace erastages {

// The registered stage ids, timeline order (schemas/timeframes).
inline std::vector<std::string> const& RegisteredStages()
{
    static std::vector<std::string> const stages = {
        "vanilla-1.1",
        "vanilla-1.2",
        "vanilla-1.3",
        "vanilla-1.4",
        "vanilla-1.5",
        "vanilla-1.6",
        "vanilla-1.7",
        "vanilla-1.8",
        "vanilla-1.9",
        "vanilla-1.10",
        "vanilla-1.11",
        "vanilla-1.12.1",
        "tbc-2.0.1",
        "tbc-2.1",
        "tbc-2.2",
        "tbc-2.3",
        "tbc-2.4.3",
        "wotlk-3.0.2",
        "wotlk-3.1",
        "wotlk-3.2",
        "wotlk-3.3",
        "wotlk-3.3.5a",
    };
    return stages;
}

// The DEFAULT stage: the shipped qualified lane's final patch
// (design section 1). An unset/unknown stage resolves here.
inline std::string const& DefaultStage()
{
    static std::string const stage("vanilla-1.12.1");
    return stage;
}

inline bool IsDefaultStage(std::string const& stage)
{
    return stage.empty() || stage == DefaultStage();
}

// The patch label of one stage ("vanilla-1.5" -> "1.5"), or an
// empty string for an unregistered stage. Drives the stage-era
// instruction line (EraStageLine).
inline std::string PatchLabel(std::string const& stage)
{
    if (stage == "vanilla-1.1") return "1.1";
    if (stage == "vanilla-1.2") return "1.2";
    if (stage == "vanilla-1.3") return "1.3";
    if (stage == "vanilla-1.4") return "1.4";
    if (stage == "vanilla-1.5") return "1.5";
    if (stage == "vanilla-1.6") return "1.6";
    if (stage == "vanilla-1.7") return "1.7";
    if (stage == "vanilla-1.8") return "1.8";
    if (stage == "vanilla-1.9") return "1.9";
    if (stage == "vanilla-1.10") return "1.10";
    if (stage == "vanilla-1.11") return "1.11";
    if (stage == "vanilla-1.12.1") return "1.12.1";
    if (stage == "tbc-2.0.1") return "2.0.1";
    if (stage == "tbc-2.1") return "2.1";
    if (stage == "tbc-2.2") return "2.2";
    if (stage == "tbc-2.3") return "2.3";
    if (stage == "tbc-2.4.3") return "2.4.3";
    if (stage == "wotlk-3.0.2") return "3.0.2";
    if (stage == "wotlk-3.1") return "3.1";
    if (stage == "wotlk-3.2") return "3.2";
    if (stage == "wotlk-3.3") return "3.3";
    if (stage == "wotlk-3.3.5a") return "3.3.5a";
    return std::string();
}

inline std::vector<std::string> const& kEraSet0_AlwaysBan()
{
    static std::vector<std::string> const terms = {
        "shattrath",
        "acherus",
    };
    return terms;
}

inline std::vector<std::pair<std::string, bool>> const& kEraSet0_Senses()
{
    static std::vector<std::pair<std::string, bool>> const phrases = {
        { "flying mount", false },
        { "flying mounts", false },
        { "portal is open", true },
        { "portals are open", true },
        { "portal has opened", true },
        { "portals have opened", true },
        { "gilneas opened", true },
        { "gilneas is open", true },
        { "gilneas has opened", true },
        { "playable worgen", false },
        { "dalaran floats", false },
        { "sewers of dalaran", false },
        { "dalaran's sewers", false },
        { "ebon blade", false },
        { "wrath of the lich king", false },
        { "mists of pandaria", false },
        { "warlords of draenor", false },
        { "zereth mortis", false },
        { "korthia", false },
        { "revendreth", false },
        { "maldraxxus", false },
        { "ardenweald", false },
        { "nazjatar", false },
        { "hellfire peninsula", false },
        { "zangarmarsh", false },
        { "terokkar forest", false },
        { "blade's edge", false },
        { "netherstorm", false },
        { "shadowmoon valley", false },
    };
    return phrases;
}

inline std::vector<std::string> const& kEraSet1_AlwaysBan()
{
    static std::vector<std::string> const terms = {
        "acherus",
    };
    return terms;
}

inline std::vector<std::pair<std::string, bool>> const& kEraSet1_Senses()
{
    static std::vector<std::pair<std::string, bool>> const phrases = {
        { "dalaran floats", false },
        { "sewers of dalaran", false },
        { "dalaran's sewers", false },
        { "ebon blade", false },
        { "wrath of the lich king", false },
        { "mists of pandaria", false },
        { "warlords of draenor", false },
        { "zereth mortis", false },
        { "korthia", false },
        { "revendreth", false },
        { "maldraxxus", false },
        { "ardenweald", false },
        { "nazjatar", false },
        { "playable worgen", false },
        { "gilneas opened", true },
        { "gilneas is open", true },
        { "gilneas has opened", true },
        { "argent tournament", false },
        { "borean tundra", false },
        { "crystalsong", false },
        { "dragonblight", false },
        { "ebon hold", false },
        { "frenzyheart", false },
        { "howling fjord", false },
        { "icecrown", false },
        { "sholazar basin", false },
        { "storm peaks", false },
        { "taunka", false },
        { "tuskarr", false },
        { "wintergrasp", false },
        { "vortex pinnacle", false },
        { "hodir", false },
        { "valiance keep", false },
        { "valgarde", false },
        { "wintergarde", false },
        { "utgarde", false },
        { "grizzly hills", false },
        { "transitus shield", false },
        { "ramkahen", false },
        { "westguard keep", false },
        { "kamagua", false },
        { "throne of the tides", false },
        { "amber ledge", false },
        { "blackrock caverns", false },
        { "halls of origination", false },
        { "vashj'ir", false },
        { "ny'alotha", false },
        { "stormsong valley", false },
        { "drustvar", false },
        { "vol'dun", false },
        { "gilneas city", false },
        { "kezan", false },
        { "lost isles", false },
        { "stonecore", false },
        { "tol barad", false },
        { "wandering isle", false },
        { "bilgewater", false },
        { "inscription", false },
        { "argent crusade", false },
        { "saronite", false },
        { "the skybreaker", false },
        { "sons of hodir", false },
        { "onslaught harbor", false },
        { "timeless isle", false },
    };
    return phrases;
}

inline std::vector<std::string> const& kEraSet2_AlwaysBan()
{
    static std::vector<std::string> const terms = {
        "vashj'ir",
        "ny'alotha",
    };
    return terms;
}

inline std::vector<std::pair<std::string, bool>> const& kEraSet2_Senses()
{
    static std::vector<std::pair<std::string, bool>> const phrases = {
        { "gilneas opened", true },
        { "gilneas is open", true },
        { "gilneas has opened", true },
        { "playable worgen", false },
        { "gilneas city", false },
        { "kezan", false },
        { "lost isles", false },
        { "stonecore", false },
        { "tol barad", false },
        { "wandering isle", false },
        { "stormsong valley", false },
        { "drustvar", false },
        { "vol'dun", false },
        { "mists of pandaria", false },
        { "warlords of draenor", false },
        { "zereth mortis", false },
        { "korthia", false },
        { "revendreth", false },
        { "maldraxxus", false },
        { "ardenweald", false },
        { "nazjatar", false },
        { "battle for azeroth", false },
        { "shadowlands", false },
        { "the war within", false },
        { "vortex pinnacle", false },
        { "ramkahen", false },
        { "throne of the tides", false },
        { "blackrock caverns", false },
        { "halls of origination", false },
        { "bilgewater", false },
        { "timeless isle", false },
    };
    return phrases;
}

// The per-stage era class resolution (design section 5). An
// unregistered stage falls open to the grounded default - the
// settings layer never persists one, and the conf consumer
// double-guards.
inline std::vector<std::string> const& AlwaysBanTermsFor(std::string const& stage)
{
    if (stage == "vanilla-1.1") return kEraSet0_AlwaysBan();
    if (stage == "vanilla-1.2") return kEraSet0_AlwaysBan();
    if (stage == "vanilla-1.3") return kEraSet0_AlwaysBan();
    if (stage == "vanilla-1.4") return kEraSet0_AlwaysBan();
    if (stage == "vanilla-1.5") return kEraSet0_AlwaysBan();
    if (stage == "vanilla-1.6") return kEraSet0_AlwaysBan();
    if (stage == "vanilla-1.7") return kEraSet0_AlwaysBan();
    if (stage == "vanilla-1.8") return kEraSet0_AlwaysBan();
    if (stage == "vanilla-1.9") return kEraSet0_AlwaysBan();
    if (stage == "vanilla-1.10") return kEraSet0_AlwaysBan();
    if (stage == "vanilla-1.11") return kEraSet0_AlwaysBan();
    if (stage == "vanilla-1.12.1") return kEraSet0_AlwaysBan();
    if (stage == "tbc-2.0.1") return kEraSet1_AlwaysBan();
    if (stage == "tbc-2.1") return kEraSet1_AlwaysBan();
    if (stage == "tbc-2.2") return kEraSet1_AlwaysBan();
    if (stage == "tbc-2.3") return kEraSet1_AlwaysBan();
    if (stage == "tbc-2.4.3") return kEraSet1_AlwaysBan();
    if (stage == "wotlk-3.0.2") return kEraSet2_AlwaysBan();
    if (stage == "wotlk-3.1") return kEraSet2_AlwaysBan();
    if (stage == "wotlk-3.2") return kEraSet2_AlwaysBan();
    if (stage == "wotlk-3.3") return kEraSet2_AlwaysBan();
    if (stage == "wotlk-3.3.5a") return kEraSet2_AlwaysBan();
    return kEraSet0_AlwaysBan();
}

inline std::vector<std::pair<std::string, bool>> const& SensePhrasesFor(std::string const& stage)
{
    if (stage == "vanilla-1.1") return kEraSet0_Senses();
    if (stage == "vanilla-1.2") return kEraSet0_Senses();
    if (stage == "vanilla-1.3") return kEraSet0_Senses();
    if (stage == "vanilla-1.4") return kEraSet0_Senses();
    if (stage == "vanilla-1.5") return kEraSet0_Senses();
    if (stage == "vanilla-1.6") return kEraSet0_Senses();
    if (stage == "vanilla-1.7") return kEraSet0_Senses();
    if (stage == "vanilla-1.8") return kEraSet0_Senses();
    if (stage == "vanilla-1.9") return kEraSet0_Senses();
    if (stage == "vanilla-1.10") return kEraSet0_Senses();
    if (stage == "vanilla-1.11") return kEraSet0_Senses();
    if (stage == "vanilla-1.12.1") return kEraSet0_Senses();
    if (stage == "tbc-2.0.1") return kEraSet1_Senses();
    if (stage == "tbc-2.1") return kEraSet1_Senses();
    if (stage == "tbc-2.2") return kEraSet1_Senses();
    if (stage == "tbc-2.3") return kEraSet1_Senses();
    if (stage == "tbc-2.4.3") return kEraSet1_Senses();
    if (stage == "wotlk-3.0.2") return kEraSet2_Senses();
    if (stage == "wotlk-3.1") return kEraSet2_Senses();
    if (stage == "wotlk-3.2") return kEraSet2_Senses();
    if (stage == "wotlk-3.3") return kEraSet2_Senses();
    if (stage == "wotlk-3.3.5a") return kEraSet2_Senses();
    return kEraSet0_Senses();
}

// The per-flavor prompt-pack identity world title (W3.2),
// resolved from the stage's flavor. The trained renderers
// (SysmForCard) speak the ACTIVE flavor's world; the default
// stage's output stays byte-identical to the frozen trained
// wording ("World of Warcraft: Classic"). The titles are
// data in schemas/flavor.json llm.prompt_identity - a
// flavor without one fails the generator loud.
inline std::string ExpansionTitleFor(std::string const& stage)
{
    if (stage.rfind("vanilla-", 0) == 0) return "World of Warcraft: Classic";
    if (stage.rfind("tbc-", 0) == 0) return "World of Warcraft: The Burning Crusade";
    if (stage.rfind("wotlk-", 0) == 0) return "World of Warcraft: Wrath of the Lich King";
    return "World of Warcraft: Classic";
}

} // namespace erastages
} // namespace pocketllm

#endif // _PlayerbotLlmEraStages_generated_h