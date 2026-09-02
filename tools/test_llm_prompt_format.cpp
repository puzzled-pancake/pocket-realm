/*
 * Host battery for the A4/A5 trained prompt-format renderer (S4 gate).
 * Two modes, exit-code driven:
 *
 *   bytediff <vectors.json>   THE STAGE GATE: render every vector row via
 *                             the SHIPPED header (PlayerbotLlmPrompt.h)
 *                             and diff against the banklib-rendered
 *                             expectations; every row must match with at
 *                             most whitespace differences, and the report
 *                             counts byte-exact rows separately.
 *   bodydump <vectors.json>   request-body invariants: BuildChatRequestBody
 *                             output re-parses as JSON (via the shipped
 *                             PlayerbotLlmJson.h), system/turn order is
 *                             preserved, the current user turn is LAST,
 *                             sampling/kwarg fields land in the body, and
 *                             providerSafe/thinkingKwargs toggle exactly
 *                             the documented fields.
 *
 * Compiled by tests/test_llm_prompt_format.py with -std=c++11 directly
 * against the shipped headers - the host always tests the shipped code,
 * never a copy.
 */
#include "PlayerbotLlmPrompt.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

using namespace pocketllm;

static int g_failures = 0;
#define CHECK(cond, msg) do { \
    if (!(cond)) { std::printf("FAIL %s (line %d)\n", msg, __LINE__); ++g_failures; } \
} while (0)

// minimal JSON value walker over the shipped parser (detail is accessible
// from the header; the harness only reads)
using JV = detail::JsonValue;

static std::string Str(JV const* v)
{
    return v && v->type == detail::JSON_STRING ? v->str : std::string();
}

static std::string NormalizeWs(std::string const& s)
{
    std::string out;
    out.reserve(s.size());
    bool ws = false;
    for (char c : s)
    {
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r')
        {
            ws = !out.empty();
            continue;
        }
        if (ws) { out += ' '; ws = false; }
        out += c;
    }
    while (!out.empty() && out.back() == ' ')
        out.pop_back();
    return out;
}

static std::vector<std::string> StrArray(JV const* v)
{
    std::vector<std::string> out;
    if (v && v->type == detail::JSON_ARRAY)
        for (auto const& item : v->items)
            if (item.type == detail::JSON_STRING)
                out.push_back(item.str);
    return out;
}

struct Row
{
    PromptPersona persona;
    PromptPlayer player;
    int tier = 3;
    std::string absence;
    std::vector<std::string> facts;
    std::string playerLine, state, fills, extra;
    std::vector<std::string> says, events, results, mems, lines;
    std::string expectedSysm, expectedUser;
    int idx = 0;
};

static bool LoadRows(char const* path, std::vector<Row>& rows)
{
    FILE* f = std::fopen(path, "rb");
    if (!f)
        return false;
    std::string text;
    char buf[4096];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0)
        text.append(buf, n);
    std::fclose(f);

    JV root;
    if (!detail::ParseJson(text, root) || root.type != detail::JSON_ARRAY)
        return false;
    for (auto const& item : root.items)
    {
        Row r;
        r.idx = item.Find("idx") && item.Find("idx")->type == detail::JSON_NUMBER
            ? std::atoi(item.Find("idx")->number.c_str()) : 0;
        JV const* card = item.Find("card");
        JV const* player = item.Find("player");
        r.persona.id = Str(card->Find("id"));
        r.persona.name = Str(card->Find("name"));
        r.persona.race = Str(card->Find("race"));
        r.persona.cls = Str(card->Find("class_"));
        r.persona.role = Str(card->Find("role"));
        r.persona.zone = Str(card->Find("zone"));
        r.persona.companion = card->Find("companion") &&
            card->Find("companion")->type == detail::JSON_BOOL &&
            card->Find("companion")->boolean;
        r.persona.bible = Str(card->Find("bible"));
        r.persona.backstory = Str(card->Find("backstory"));
        r.persona.quirks = Str(card->Find("quirks"));
        r.persona.never = Str(card->Find("never"));
        r.persona.tierNote = Str(card->Find("tier_note"));
        r.player.name = Str(player->Find("name"));
        r.player.sex = Str(player->Find("sex"));
        r.player.race = Str(player->Find("race"));
        r.player.cls = Str(player->Find("class_"));
        r.player.level = player->Find("level")
            ? std::atoi(player->Find("level")->number.c_str()) : 1;
        r.tier = item.Find("tier") ? std::atoi(item.Find("tier")->number.c_str()) : 3;
        r.absence = Str(item.Find("absence"));
        r.facts = StrArray(item.Find("facts"));
        JV const* user = item.Find("user");
        r.playerLine = Str(user->Find("player_line"));
        r.says = StrArray(user->Find("says"));
        r.events = StrArray(user->Find("events"));
        r.results = StrArray(user->Find("results"));
        r.state = Str(user->Find("state"));
        r.mems = StrArray(user->Find("mems"));
        r.lines = StrArray(user->Find("lines"));
        r.fills = Str(user->Find("fills"));
        r.extra = Str(user->Find("extra"));
        r.expectedSysm = Str(item.Find("expected_sysm"));
        r.expectedUser = Str(item.Find("expected_user"));
        rows.push_back(r);
    }
    return !rows.empty();
}

static int RunByteDiff(char const* path)
{
    std::vector<Row> rows;
    if (!LoadRows(path, rows))
    {
        std::printf("cannot load vectors: %s\n", path);
        return 1;
    }
    size_t sysmExact = 0, userExact = 0, wsOnly = 0, mismatches = 0;
    for (Row const& r : rows)
    {
        std::string sysm = SysmForCard(r.persona, r.player, r.tier, r.absence, r.facts);
        std::string user = ComposeUserTurn(r.playerLine, r.says, r.events,
            r.results, r.state, r.mems, r.lines, r.fills, r.extra);
        bool sExact = sysm == r.expectedSysm;
        bool uExact = user == r.expectedUser;
        if (sExact) ++sysmExact;
        if (uExact) ++userExact;
        if (sExact && uExact) continue;
        if (NormalizeWs(sysm) == NormalizeWs(r.expectedSysm) &&
            NormalizeWs(user) == NormalizeWs(r.expectedUser))
        {
            ++wsOnly;
            continue;
        }
        ++mismatches;
        std::printf("row %d mismatch\n  sysm exact=%d\n", r.idx, (int)sExact);
        // show the first divergence for triage
        std::string const& a = sysm, & b = r.expectedSysm;
        size_t d = 0;
        while (d < a.size() && d < b.size() && a[d] == b[d]) ++d;
        std::printf("  first diff at %zu:\n   got: %.60s\n   exp: %.60s\n",
            d, a.substr(d, 60).c_str(), b.substr(d, 60).c_str());
        if (user != r.expectedUser)
        {
            size_t e = 0;
            std::string const& c = user, & f = r.expectedUser;
            while (e < c.size() && e < f.size() && c[e] == f[e]) ++e;
            std::printf("  user first diff at %zu:\n   got: %.60s\n   exp: %.60s\n",
                e, c.substr(e, 60).c_str(), f.substr(e, 60).c_str());
        }
    }
    std::printf("{");
    std::printf("\"rows\": %zu, ", rows.size());
    std::printf("\"sysm_byte_exact\": %zu, ", sysmExact);
    std::printf("\"user_byte_exact\": %zu, ", userExact);
    std::printf("\"whitespace_only\": %zu, ", wsOnly);
    std::printf("\"mismatches\": %zu", mismatches);
    std::printf("}\n");
    if (mismatches == 0 && rows.size() >= 20)
        std::printf("prompt-format byte-diff gate passed\n");
    else if (mismatches == 0)
        std::printf("prompt-format byte-diff passed but rows < 20 (gate needs 20)\n");
    return (mismatches == 0 && rows.size() >= 20) ? 0 : 1;
}

static int RunBodyDump(char const* path)
{
    std::vector<Row> rows;
    if (!LoadRows(path, rows))
    {
        std::printf("cannot load vectors: %s\n", path);
        return 1;
    }

    // variants across the sampling surface
    RequestSampling s;
    s.temperature = 0.7f; s.topP = 0.8f; s.topK = 20;
    s.repeatPenalty = 1.0f; s.presencePenalty = 1.0f;
    s.minP = 0.05f; s.maxTokens = 120;

    size_t bodiesChecked = 0;
    for (Row const& r : rows)
    {
        std::string sysm = SysmForCard(r.persona, r.player, r.tier, r.absence, r.facts);
        std::string user = ComposeUserTurn(r.playerLine, r.says, r.events,
            r.results, r.state, r.mems, r.lines, r.fills, r.extra);
        std::vector<HistoryTurn> history;
        history.push_back(HistoryTurn{false, "earlier player line"});
        history.push_back(HistoryTurn{true, "earlier bot reply"});
        history.push_back(HistoryTurn{false, "latest player line"});

        s.thinkingKwargs = (r.idx % 2) == 0;
        s.providerSafe = false;
        std::string body = BuildChatRequestBody("local", sysm, history, user, s);
        ++bodiesChecked;

        // the body must re-parse with the shipped JSON client
        JV root;
        CHECK(detail::ParseJson(body, root) && root.type == detail::JSON_OBJECT,
              "body parses as a JSON object");
        JV const* messages = root.Find("messages");
        CHECK(messages && messages->type == detail::JSON_ARRAY &&
              messages->items.size() == 5, "5 messages: system + 3 history + current");
        if (messages && messages->items.size() == 5)
        {
            CHECK(Str(messages->items[0].Find("role")) == "system", "first message is system");
            CHECK(Str(messages->items[0].Find("content")) == sysm, "system content round-trips");
            CHECK(Str(messages->items[1].Find("role")) == "user", "history turn 1 user");
            CHECK(Str(messages->items[2].Find("role")) == "assistant", "history turn 2 assistant");
            CHECK(Str(messages->items[4].Find("role")) == "user", "CURRENT turn is last");
            CHECK(Str(messages->items[4].Find("content")) == user, "user content round-trips");
        }
        CHECK(body.find("\"max_tokens\":120,") != std::string::npos, "max_tokens lands");
        CHECK(body.find("\"temperature\":0.7,") != std::string::npos, "temperature lands");
        CHECK(body.find("\"top_k\":20,") != std::string::npos, "top_k lands");
        CHECK(body.find("\"min_p\":0.05,") != std::string::npos, "min_p lands");
        CHECK(body.find("\"presence_penalty\":1,") != std::string::npos, "presence_penalty lands");
        CHECK(body.find("\"repeat_penalty\":1,") != std::string::npos, "repeat_penalty lands");
        CHECK(body.find("\"stream\":false}") != std::string::npos, "stream:false tail");
        if (s.thinkingKwargs)
            CHECK(body.find("\"chat_template_kwargs\":{\"enable_thinking\":false}") !=
                  std::string::npos, "thinking kwargs land when flagged");
        else
            CHECK(body.find("chat_template_kwargs") == std::string::npos,
                  "no thinking kwargs when unflagged");

        // providerSafe strips the llama.cpp-only fields
        s.providerSafe = true;
        std::string ext = BuildChatRequestBody("gpt-test", sysm, history, user, s);
        CHECK(ext.find("top_k") == std::string::npos, "providerSafe strips top_k");
        CHECK(ext.find("repeat_penalty") == std::string::npos, "providerSafe strips repeat_penalty");
        CHECK(ext.find("min_p") == std::string::npos, "providerSafe strips min_p");
        CHECK(ext.find("presence_penalty") == std::string::npos, "providerSafe strips presence_penalty");
        CHECK(ext.find("\"model\":\"gpt-test\"") != std::string::npos, "model lands");
        CHECK(ext.find("\"temperature\":0.7,") != std::string::npos, "temperature kept");
        s.providerSafe = false;
    }

    // S2's retry splice must find the last user message in the built body
    {
        Row const& r = rows.front();
        std::string sysm = SysmForCard(r.persona, r.player, r.tier, r.absence, r.facts);
        std::string user = ComposeUserTurn(r.playerLine, {}, {}, {}, r.state, {}, {}, "", "");
        std::vector<HistoryTurn> history;
        history.push_back(HistoryTurn{true, "bot line"});
        std::string body = BuildChatRequestBody("local", sysm, history, user, s);
        std::string copy = body;
        CHECK(AppendInstructionToLastUserMessage(copy, " Answer directly."),
              "retry splice targets the built body's current user turn");
        JV root;
        CHECK(detail::ParseJson(copy, root), "spliced built body re-parses");
        JV const* m = root.Find("messages");
        CHECK(m && m->items.size() == 3 &&
              Str(m->items[2].Find("content")) == user + " Answer directly.",
              "spliced current turn gained exactly the instruction");
    }

    std::printf("{\"bodies_checked\": %zu}\n", bodiesChecked);
    if (!g_failures)
        std::printf("request-body invariants passed\n");
    return g_failures ? 1 : 0;
}

int main(int argc, char** argv)
{
    if (argc < 3)
    {
        std::printf("usage: %s bytediff|bodydump <vectors.json>\n", argv[0]);
        return 2;
    }
    if (std::strcmp(argv[1], "bodydump") == 0)
        return RunBodyDump(argv[2]);
    return RunByteDiff(argv[2]);
}
