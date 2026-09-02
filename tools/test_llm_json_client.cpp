/*
 * Host battery for the A9 real-JSON response client (S2). Three modes,
 * exit-code driven:
 *
 *   invariants        envelope edge cases: content null/missing/empty,
 *                     reasoning_content, finish_reason variants, legacy
 *                     /v1/completions text shape, error envelopes, pretty
 *                     printing, non-JSON bodies; retry splice correctness;
 *                     truncation trim
 *   battery [n]       the A9 gate: n (default 100) generated replies with
 *                     escaped quotes, newlines, tabs, raw UTF-8 and
 *                     \uXXXX escapes (incl. surrogate pairs), backslashes
 *                     and tool markers must parse with ZERO failures and
 *                     byte-exact content; the same replies run through a
 *                     replica of the old regex extraction to document the
 *                     failures the JSON path removes
 *   fuzz [iters]      adversarial mutations of valid envelopes and request
 *                     bodies: the parser never crashes, never reads past
 *                     the buffer, and a successful retry splice always
 *                     leaves a body that re-parses with exactly the
 *                     instruction appended
 *
 * Compiled by tests/test_llm_json_client.py with -std=c++11 directly
 * against the shipped header (native/patches/playerbots/PlayerbotLlmJson.h)
 * - the host always tests the shipped code, never a copy.
 */
#include "PlayerbotLlmJson.h"

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

// ------------------------------------------------------------- tiny rng ----
static unsigned long long g_rngState = 0x9E3779B97F4A7C15ull;
static unsigned long long NextU64()
{
    g_rngState += 0x9E3779B97F4A7C15ull;
    unsigned long long z = g_rngState;
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
    return z ^ (z >> 31);
}
static size_t NextBelow(size_t bound) { return (size_t)(NextU64() % bound); }

// --------------------------------------------------- reply content pool ----
// Every piece a tuned model actually emits in production: dwarven speech,
// keyed tool lines, escaped quotes, newlines, unicode in raw UTF-8.
static const char* const kWords[] = {
    "Aye,", "the", "forge", "burns", "hot", "tonight.", "Five", "silver,",
    "Brannoc,", "no", "less.", "Coal's", "damp", "again.",
    "Mind", "the", "kobolds", "by", "the", "bridge.",
    "\xc3\x81r", "na", "m\xc3\xb3r", "-", "old", "words,", "older", "debts.",
    "\xe6\x88\x91\xe4\xbb\xac", "\xd0\xb4\xd0\xbe\xd0\xbb\xd0\xb3",
};
static const size_t kWordCount = sizeof(kWords) / sizeof(kWords[0]);

static const char* const kToolLines[] = {
    "<<log_fact text=\"saved enough for the forge permit\" category=\"goal\">>",
    "<<adjust_sentiment direction=\"-1\" reason=\"called my work pig iron\">>",
    "<<perform_emote emote=\"laugh\">>",
    "<<share_gossip text=\"murloc raid on the east docks\">>",
};
static const size_t kToolCount = sizeof(kToolLines) / sizeof(kToolLines[0]);

static const char* const kQuoted[] = {
    "He said \"pay me first\", can you believe it.",
    "\"The militia Captain\" wants his order early.",
    "Quote me on this: \"done is done\".",
};

static std::string MakeReplyContent(bool withNewlines)
{
    std::string out;
    const size_t pieces = 6 + NextBelow(10);
    for (size_t i = 0; i < pieces; ++i)
    {
        if (!out.empty())
            out += withNewlines && (NextU64() & 3) == 0 ? "\n" : " ";
        switch (NextBelow(8))
        {
            case 0: case 1: case 2: case 3:
                out += kWords[NextBelow(kWordCount)];
                break;
            case 4:
                out += kQuoted[NextBelow(sizeof(kQuoted) / sizeof(kQuoted[0]))];
                break;
            case 5:
                out += kToolLines[NextBelow(kToolCount)];
                break;
            case 6:
                out += "\xf0\x9f\x98\x80"; // 4-byte UTF-8 (astral plane)
                break;
            default:
                out += "back\\slash";
                break;
        }
    }
    if (NextU64() & 1)
        out += "\t";
    return out;
}

// ------------------------------------------------------------ envelope -----
static const char* const kFinishes[] = { "stop", "length" };

static std::string MakeEnvelope(const std::string& content, const char* finish,
    bool pretty)
{
    std::string escaped = EscapeJsonString(content);
    if (pretty)
    {
        return "{\n  \"choices\": [\n    {\n      \"message\": {\n        "
            "\"content\": \"" + escaped + "\",\n        \"role\": \"assistant\"\n      },\n      "
            "\"finish_reason\": \"" + std::string(finish) + "\",\n      \"index\": 0\n    }\n  ],\n  "
            "\"created\": 1770000000,\n  \"model\": \"local\",\n  \"usage\": {\n    "
            "\"prompt_tokens\": 1200,\n    \"completion_tokens\": 96\n  }\n}\n";
    }
    return std::string("{\"id\":\"chatcmpl-1\",\"choices\":[{\"message\":")
        + "{\"role\":\"assistant\",\"content\":\"" + escaped + "\"},"
        + "\"finish_reason\":\"" + finish + "\",\"index\":0}],"
        + "\"created\":1770000000,\"model\":\"local\","
        + "\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":96}}";
}

// Same envelope with the content spelled as \\uXXXX escapes (incl. an
// astral emoji as a surrogate pair) - must decode to the same bytes.
static std::string MakeUnicodeEscapeEnvelope()
{
    return "{\"choices\":[{\"message\":{\"content\":"
        "\"Aye, caf\\u00e9 \\u2014 \\u4e2d\\u6587 \\ud83d\\ude00 done.\"},"
        "\"finish_reason\":\"stop\"}]}";
}

// Encode arbitrary text as a JSON string using \\uXXXX escapes for every
// non-ASCII codepoint (astral planes as surrogate pairs) - the escaped
// flavor some providers emit. Must decode to exactly the original bytes.
static std::string EncodeAllEscaped(const std::string& text)
{
    std::string out;
    for (size_t i = 0; i < text.size();)
    {
        unsigned char c = (unsigned char)text[i];
        if (c < 0x80)
        {
            char buf[8];
            if (c == '"') out += "\\\"";
            else if (c == '\\') out += "\\\\";
            else if (c == '\n') out += "\\n";
            else if (c == '\t') out += "\\t";
            else if (c < 0x20 || c >= 0x7f)
            {
                std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                out += buf;
            }
            else
                out += (char)c;
            ++i;
            continue;
        }
        // decode the UTF-8 sequence to a codepoint, re-emit as \uXXXX
        unsigned int cp = 0;
        int extra = 0;
        if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; extra = 1; }
        else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; extra = 2; }
        else { cp = c & 0x07; extra = 3; }
        for (int k = 0; k < extra && i + 1 + k < text.size(); ++k)
            cp = (cp << 6) | ((unsigned char)text[i + 1 + k] & 0x3F);
        i += 1 + (size_t)extra;
        char buf[16];
        if (cp >= 0x10000)
        {
            unsigned int hi = 0xD800 + ((cp - 0x10000) >> 10);
            unsigned int lo = 0xDC00 + ((cp - 0x10000) & 0x3FF);
            std::snprintf(buf, sizeof(buf), "\\u%04x\\u%04x", hi, lo);
        }
        else
            std::snprintf(buf, sizeof(buf), "\\u%04x", cp);
        out += buf;
    }
    return out;
}

static std::string MakeEnvelopeEscaped(const std::string& content, const char* finish)
{
    return std::string("{\"choices\":[{\"message\":")
        + "{\"role\":\"assistant\",\"content\":\"" + EncodeAllEscaped(content) + "\"},"
        + "\"finish_reason\":\"" + finish + "\",\"index\":0}]}";
}

// ------------------------------------------------- old regex path replica --
// Mirrors the pre-A9 extraction (extractAfterPattern/extractBeforePattern +
// the escaped-quote ReplaceAll in ParseResponse) using the conf values the
// app shipped before S2. Kept ONLY to document, in the battery output, the
// failure classes the JSON path removes.
namespace legacy_regex
{
    static std::string AfterPattern(const std::string& content, const char* /*startPattern*/)
    {
        // replica of extractAfterPattern for the shipped start pattern
        // `("content":\s*["])`: find "content":, skip spaces, cut after quote
        size_t key = content.find("\"content\":");
        if (key == std::string::npos)
            return "";
        size_t pos = key + 10;
        while (pos < content.size() && (content[pos] == ' ' || content[pos] == '\t'))
            ++pos;
        if (pos >= content.size() || content[pos] != '"')
            return "";
        return content.substr(pos + 1);
    }

    static std::string ReplaceAll(std::string const& text, const char* from, const char* to)
    {
        std::string out = text;
        std::string f(from), t(to);
        size_t pos = 0;
        while ((pos = out.find(f, pos)) != std::string::npos)
        {
            out.replace(pos, f.size(), t);
            pos += t.size();
        }
        return out;
    }

    static std::string BeforePattern(const std::string& content)
    {
        // end pattern `(")`: cut at the first remaining quote
        size_t pos = content.find('"');
        return pos == std::string::npos ? content : content.substr(0, pos);
    }

    static std::string Extract(const std::string& body)
    {
        std::string after = AfterPattern(body, "(\"content\":\\s*[\"])");
        after = ReplaceAll(after, "\\\"", "'");
        return BeforePattern(after);
    }
}

// ------------------------------------------------------------ invariants ---
static void RunInvariants()
{
    // canonical envelope
    {
        CompletionEnvelope e = ParseCompletionEnvelope(
            MakeEnvelope(std::string("Aye, five silver.") + "\n" + "No less.", "stop", false));
        CHECK(e.parsed, "canonical envelope parses");
        CHECK(e.contentIsString, "content is a string");
        CHECK(e.content == std::string("Aye, five silver.") + "\n" + "No less.",
              "newline in content decodes byte-exact");
        CHECK(!e.finishLength, "stop is not truncation");
        CHECK(ContentUsable(e), "canonical content is usable");
    }
    // truncation flag
    {
        CompletionEnvelope e = ParseCompletionEnvelope(
            MakeEnvelope("Half a sentence about the forge", "length", false));
        CHECK(e.finishLength, "length marks truncation");
    }
    // pretty-printed envelope (llama-server never emits this, providers do)
    {
        CompletionEnvelope e = ParseCompletionEnvelope(
            MakeEnvelope("pretty printed reply", "stop", true));
        CHECK(e.parsed && e.content == "pretty printed reply", "pretty JSON parses");
    }
    // reasoning-only envelope: the §1.4 pinned-base-model failure mode
    {
        std::string body = "{\"choices\":[{\"message\":{\"content\":\"\","
            "\"reasoning_content\":\"Thinking Process: the player wants...\"},"
            "\"finish_reason\":\"length\"}]}";
        CompletionEnvelope e = ParseCompletionEnvelope(body);
        CHECK(e.parsed, "reasoning envelope parses");
        CHECK(e.reasoningPresent, "non-empty reasoning_content detected");
        CHECK(!ContentUsable(e), "empty content is not usable");
        CHECK(e.finishLength, "budget burn marks truncation");
    }
    // content null / missing / non-string
    {
        CompletionEnvelope n = ParseCompletionEnvelope(
            "{\"choices\":[{\"message\":{\"content\":null},\"finish_reason\":\"stop\"}]}");
        CHECK(n.parsed && !n.contentIsString && !ContentUsable(n), "null content is not usable");
        CompletionEnvelope m = ParseCompletionEnvelope(
            "{\"choices\":[{\"message\":{\"role\":\"assistant\"},\"finish_reason\":\"stop\"}]}");
        CHECK(!m.parsed, "message without content is not a voicable envelope");
        CompletionEnvelope arr = ParseCompletionEnvelope(
            "{\"choices\":[{\"message\":{\"content\":[\"a\"]},\"finish_reason\":\"stop\"}]}");
        // content-parts arrays (vision-style replies) ARE chat envelopes -
        // parsed, but nothing voicable: the client fails quiet rather than
        // treating the body as a foreign shape
        CHECK(arr.parsed && !arr.contentIsString && !ContentUsable(arr),
              "array-typed content parses but is not usable");
    }
    // finish_reason null / missing
    {
        CompletionEnvelope f = ParseCompletionEnvelope(
            "{\"choices\":[{\"message\":{\"content\":\"x\"},\"finish_reason\":null}]}");
        CHECK(f.parsed && !f.finishLength, "null finish_reason is not truncation");
    }
    // multiple choices: the first one wins (n=1 contract)
    {
        CompletionEnvelope e = ParseCompletionEnvelope(
            "{\"choices\":[{\"message\":{\"content\":\"first\"},\"finish_reason\":\"stop\"},"
            "{\"message\":{\"content\":\"second\"},\"finish_reason\":\"stop\"}]}");
        CHECK(e.parsed && e.content == "first", "first choice wins");
    }
    // legacy /v1/completions text shape
    {
        CompletionEnvelope e = ParseCompletionEnvelope(
            "{\"choices\":[{\"text\":\"completion reply\",\"finish_reason\":\"length\"}]}");
        CHECK(e.parsed && e.content == "completion reply" && e.finishLength,
              "legacy completions text shape parses with finish_reason");
    }
    // error envelope: JSON, but nothing voicable
    {
        std::string body = "{\"error\":{\"message\":\"model not loaded\",\"code\":503}}";
        CompletionEnvelope e = ParseCompletionEnvelope(body);
        CHECK(!e.parsed, "error envelope is not a completion");
        CHECK(e.jsonParsable, "error envelope is JSON (caller must fail quiet)");
    }
    // non-JSON bodies stay on the fallback path - and the voicing gate
    {
        CHECK(!ParsesAsJson(""), "empty body is not JSON");
        CHECK(!ParsesAsJson("error"), "transport sentinel is not JSON");
        CHECK(!ParsesAsJson("<html>502 Bad Gateway</html>"), "error page is not JSON");
        CHECK(!ParsesAsJson("{\"content\":\"trailing garbage\"} oops"),
              "trailing garbage fails the whole parse");
        // legacy llama.cpp generate shape is JSON - the caller fails quiet
        CHECK(ParsesAsJson("{\"content\":\"raw text\"}"),
              "legacy generate shape is JSON (no voicable envelope)");

        // the non-JSON fallback is gated: prose passes, garbage never does
        CHECK(LooksLikeVoicableText("Aye, five silver. No less."),
              "plain prose passes the voicing gate");
        CHECK(LooksLikeVoicableText("  [Grumph grins] Aye, bring it."),
              "bracketed emote prose passes the voicing gate");
        CHECK(!LooksLikeVoicableText("<html><head><title>502</title>"),
              "HTML error page refused");
        CHECK(!LooksLikeVoicableText("{\"choices\":[{\"message\""),
              "truncated JSON remnant refused");
        CHECK(!LooksLikeVoicableText("\xEF\xBB\xBF{\"choices\":[...]}"),
              "BOM-prefixed envelope refused");
        CHECK(!LooksLikeVoicableText(""), "empty refused");
        CHECK(!LooksLikeVoicableText("   \n  "), "whitespace-only refused");
        CHECK(!LooksLikeVoicableText("ab"), "under-length refused");
        CHECK(!LooksLikeVoicableText(std::string("abc\0def", 7)),
              "NUL-bearing body refused");
        CHECK(!LooksLikeVoicableText("aa\x01\x02\x03\x04\x05" "bb"),
              "control-dense body refused");
        // round-2 hardening: size cap, bracket-lead structure, key fragments
        CHECK(!LooksLikeVoicableText(std::string(40000, 'a')),
              "oversize body refused (flood attempt)");
        CHECK(!LooksLikeVoicableText("[[[[[[[[[["),
              "unclosed bracket garbage refused");
        CHECK(!LooksLikeVoicableText("[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[["),
              "runaway bracket structure refused");
        CHECK(!LooksLikeVoicableText("\"choices\": [{\"message\": ..."),
              "leading key-fragment (JSON that lost its brace) refused");
        CHECK(LooksLikeVoicableText("\"Aye, five silver\" - pay up."),
              "quoted prose reply passes");
        CHECK(!LooksLikeVoicableText("}}}}"),
              "brace-tail garbage refused (no letter run)");
        CHECK(!LooksLikeVoicableText("}}, said the smith quietly"),
              "envelope-tail fragment with prose refused (} lead)");
        CHECK(!LooksLikeVoicableText("]], the rest of the envelope"),
              "envelope-tail fragment refused (] lead)");
        CHECK(!LooksLikeVoicableText(std::string("abc\x7f\x7f", 5)),
              "DEL-bearing body refused");
        CHECK(LooksLikeVoicableText("aye mate"),
              "short prose with a letter run passes");
    }
    // strictness: raw control chars inside strings are refused
    {
        CHECK(!ParsesAsJson("{\"a\":\"line\nbreak\"}"), "raw newline inside string refused");
        CHECK(!ParsesAsJson("{\"a\":\"tab\there\"}"), "raw tab inside string refused");
        CHECK(ParsesAsJson("{\"a\":\"escaped\\nbreak\"}"), "escaped newline accepted");
    }
    // \\uXXXX decoding incl. surrogate pairs
    {
        CompletionEnvelope e = ParseCompletionEnvelope(MakeUnicodeEscapeEnvelope());
        CHECK(e.parsed, "unicode-escaped envelope parses");
        const std::string expected =
            "Aye, caf\xc3\xa9 \xe2\x80\x94 \xe4\xb8\xad\xe6\x96\x87 \xf0\x9f\x98\x80 done.";
        CHECK(e.content == expected, "\\uXXXX and surrogate pairs decode to UTF-8");
    }
    // lone surrogate degrades to U+FFFD, does not fail the document
    {
        CompletionEnvelope e = ParseCompletionEnvelope(
            "{\"choices\":[{\"message\":{\"content\":\"x \\ud800 y\"}}]}");
        CHECK(e.parsed && e.content == "x \xef\xbf\xbd y", "lone surrogate becomes U+FFFD");
    }
    // ContentUsable voicing gate: decoded control bytes and invalid UTF-8
    // are never voiced (an embedded NUL would truncate the packet stream)
    {
        CompletionEnvelope e = ParseCompletionEnvelope(
            "{\"choices\":[{\"message\":{\"content\":\"a\\u0000b\"}}]}");
        CHECK(e.parsed && e.content == std::string("a\0b", 3),
              "NUL decodes into the content string");
        CHECK(!ContentUsable(e), "NUL-bearing content is not usable");
        CompletionEnvelope overlong = ParseCompletionEnvelope(
            "{\"choices\":[{\"message\":{\"content\":\"a\xc0\x80" "b\"}}]}");
        CHECK(overlong.parsed && !ContentUsable(overlong),
              "overlong UTF-8 encoding is not usable");
        CompletionEnvelope bare = ParseCompletionEnvelope(
            "{\"choices\":[{\"message\":{\"content\":\"a\xff" "b\"}}]}");
        CHECK(bare.parsed && !ContentUsable(bare),
              "bare invalid byte is not usable");
        CompletionEnvelope truncSeq = ParseCompletionEnvelope(
            "{\"choices\":[{\"message\":{\"content\":\"caf\xc3\"}}]}");
        CHECK(truncSeq.parsed && !ContentUsable(truncSeq),
              "truncated multibyte sequence is not usable");
        CompletionEnvelope clean = ParseCompletionEnvelope(
            "{\"choices\":[{\"message\":{\"content\":"
            "\"caf\xc3\xa9 \xf0\x9f\x98\x80 with newline\\nand tab\\t.\"}}]}");
        CHECK(clean.parsed && ContentUsable(clean),
              "clean UTF-8 with newline/tab IS usable");
        CompletionEnvelope ws = ParseCompletionEnvelope(
            "{\"choices\":[{\"message\":{\"content\":\" \\t \"}}]}");
        CHECK(ws.parsed && !ContentUsable(ws), "whitespace-only is not usable");
        // round-2 hardening: per-lead continuation ranges, C1/DEL, size cap
        {
            struct Case { const char* label; const char* bytes; bool usable; };
            const Case cases[] = {
                {"E0 80 80 overlong 3-byte", "\"\xe0\x80\x80" "x\"", false},
                {"E0 A0 80 valid 3-byte (U+0800)", "\"\xe0\xa0\x80" "x\"", true},
                {"ED 9F BF valid (U+D7FF)", "\"\xed\x9f\xbf" "x\"", true},
                {"ED A0 80 surrogate half", "\"\xed\xa0\x80" "x\"", false},
                {"F0 80 80 80 overlong 4-byte", "\"\xf0\x80\x80\x80" "x\"", false},
                {"F0 90 80 80 valid (U+10000)", "\"\xf0\x90\x80\x80" "x\"", true},
                {"F4 8F BF BF valid (U+10FFFF)", "\"\xf4\x8f\xbf\xbf" "x\"", true},
                {"F4 90 80 80 beyond U+10FFFF", "\"\xf4\x90\x80\x80" "x\"", false},
                {"C2 80 C1 control (NEL)", "\"a\xc2\x80" "b\"", false},
                {"C2 A0 valid (U+00A0)", "\"a\xc2\xa0" "b\"", true},
                {"DEL byte", "\"a\x7f" "b\"", false},
                // cross-sequence state machine: the DEL/ASCII arrives where
                // a continuation byte is required
                {"DEL mid-sequence", "\"\xe0\xa0\x7f" "x\"", false},
                {"ASCII mid-sequence", "\"\xf4\x8f" "x\"", false},
            };
            for (const Case& tc : cases)
            {
                std::string body = std::string("{\"choices\":[{\"message\":{\"content\":") +
                    tc.bytes + "}}]}";
                CompletionEnvelope ce = ParseCompletionEnvelope(body);
                bool usable = ce.parsed && ContentUsable(ce);
                if (usable != tc.usable)
                {
                    std::printf("voicing gate case '%s': expected %d got %d\n",
                        tc.label, (int)tc.usable, (int)usable);
                    ++g_failures;
                }
            }
            // a megabyte "reply" is a flood attempt, never prose
            std::string bomb(40000, 'a');
            CompletionEnvelope cb = ParseCompletionEnvelope(
                "{\"choices\":[{\"message\":{\"content\":\"" + bomb + "\"}}]}");
            CHECK(cb.parsed && !ContentUsable(cb), "oversize content is not usable");
            // exact cap boundary: 32768 passes, 32769 refuses
            {
                std::string at(32768, 'a');
                CompletionEnvelope ok = ParseCompletionEnvelope(
                    "{\"choices\":[{\"message\":{\"content\":\"" + at + "\"}}]}");
                CHECK(ok.parsed && ContentUsable(ok), "exactly-at-cap content is usable");
                std::string over(32769, 'a');
                CompletionEnvelope no = ParseCompletionEnvelope(
                    "{\"choices\":[{\"message\":{\"content\":\"" + over + "\"}}]}");
                CHECK(no.parsed && !ContentUsable(no), "one-past-cap content refuses");
            }
        }
    }

    // ---- retry splice -------------------------------------------------
    {
        // the exact request-body shape the app template emits
        std::string body = "{\"model\":\"local\",\"messages\":["
            "{\"role\":\"system\",\"content\":\"pre context\"},"
            "{\"role\":\"user\",\"content\":\"prompt post\"}],"
            "\"max_tokens\":120,\"temperature\":1,\"top_p\":0.95,\"top_k\":64,"
            "\"repeat_penalty\":1,\"cache_prompt\":true,\"stream\":false}";
        std::string copy = body;
        CHECK(AppendInstructionToLastUserMessage(copy, " Answer directly."),
              "splice succeeds on the app body");
        CHECK(copy.size() == body.size() + strlen(" Answer directly."),
              "splice is byte-minimal");
        CHECK(copy.find("prompt post Answer directly.") != std::string::npos,
              "instruction lands inside the last user content");
        // every other byte preserved: strip the inserted span and compare
        size_t at = copy.find(" Answer directly.");
        std::string round = copy.substr(0, at) + copy.substr(at + strlen(" Answer directly."));
        CHECK(round == body, "splice touches nothing but the insertion point");
    }
    {
        // content carrying escapes: the splice must insert ESCAPED text
        std::string body = "{\"messages\":[{\"role\":\"user\",\"content\":\"say \\\"hi\\\"\"}]}";
        std::string copy = body;
        CHECK(AppendInstructionToLastUserMessage(copy, " \"now\""), "splice with quotes");
        detail::JsonValue root;
        CHECK(detail::ParseJson(copy, root), "spliced body re-parses");
        const detail::JsonValue* m = root.Find("messages");
        CHECK(m && !m->items.empty() && m->items[0].Find("content") &&
              m->items[0].Find("content")->str == "say \"hi\" \"now\"",
              "spliced content decodes with the appended instruction");
    }
    {
        // assistant-last turn: the instruction must skip it to the last USER turn
        std::string body = "{\"messages\":["
            "{\"role\":\"user\",\"content\":\"first\"},"
            "{\"role\":\"assistant\",\"content\":\"reply\"},"
            "{\"role\":\"user\",\"content\":\"second\"}]}";
        std::string copy = body;
        CHECK(AppendInstructionToLastUserMessage(copy, " go"),
              "splice finds the last user turn past assistant turns");
        CHECK(copy.find("second go") != std::string::npos, "appended to the user turn");
        CHECK(copy.find("reply go") == std::string::npos, "assistant turn untouched");
    }
    {
        // no user turn / no messages / non-string content: refuse, untouched
        std::string onlySystem = "{\"messages\":[{\"role\":\"system\",\"content\":\"s\"}]}";
        std::string copy = onlySystem;
        CHECK(!AppendInstructionToLastUserMessage(copy, " x"), "no user turn refuses");
        CHECK(copy == onlySystem, "refused splice leaves body untouched");
        std::string noMessages = "{\"prompt\":\"plain completion\"}";
        CHECK(!AppendInstructionToLastUserMessage(noMessages, " x"), "no messages refuses");
        std::string arrayContent = "{\"messages\":[{\"role\":\"user\",\"content\":[\"a\"]}]}";
        CHECK(!AppendInstructionToLastUserMessage(arrayContent, " x"),
              "array-typed user content refuses");
        // the LAST user turn is the amendable one: when it is not a plain
        // string (multimodal array), amending an EARLIER user turn would
        // put the instruction on the wrong turn - refuse the whole splice
        std::string visionLast = "{\"messages\":["
            "{\"role\":\"user\",\"content\":\"first\"},"
            "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"pic\"}]}]}";
        std::string visionCopy = visionLast;
        CHECK(!AppendInstructionToLastUserMessage(visionCopy, " x"),
              "non-string LAST user turn refuses rather than amending an earlier one");
        CHECK(visionCopy == visionLast, "refused vision splice leaves body untouched");
    }

    // ---- truncation trim ----------------------------------------------
    {
        CHECK(!TrimTruncatedTail("One. Two. Three that just sto").empty(),
              "trim returns non-empty");
        CHECK(TrimTruncatedTail("One. Two. Three that just sto") == "One. Two.",
              "dangling partial sentence dropped");
        CHECK(TrimTruncatedTail("Only sentence, no terminat") == "Only sentence, no terminat",
              "no terminator keeps the text (silence is worse)");
        CHECK(TrimTruncatedTail("Done.") == "Done.", "complete reply unchanged");
        CHECK(TrimTruncatedTail("Done!  ") == "Done!", "trailing whitespace trimmed");
        CHECK(TrimTruncatedTail("") == "", "empty stays empty");
    }

    // ---- EscapeJsonString round trip ----------------------------------
    {
        std::string nasty = "quote\" back\\slash \n\t\r\b\f " "\x01\x1f" " end";
        std::string json = "\"" + EscapeJsonString(nasty) + "\"";
        detail::JsonValue root;
        CHECK(detail::ParseJson(json, root) && root.type == detail::JSON_STRING &&
              root.str == nasty, "escaped string round-trips byte-exact");
    }

    if (!g_failures)
        std::printf("json client invariants passed\n");
}

// -------------------------------------------------------------- battery ----
static void RunBattery(size_t generations)
{
    size_t jsonFailures = 0;
    size_t regexFailures = 0;
    size_t truncatedSeen = 0;
    size_t toolMarkerReplies = 0;
    size_t escapedReplies = 0;

    for (size_t i = 0; i < generations; ++i)
    {
        std::string content = MakeReplyContent(/*withNewlines=*/(i & 1) == 0);
        bool hasTool = content.find("<<") != std::string::npos;
        if (hasTool)
            ++toolMarkerReplies;
        const char* finish = kFinishes[NextBelow(2)];
        if (finish[0] == 'l')
            ++truncatedSeen;
        // every 5th envelope spells the content with \uXXXX escapes (astral
        // planes as surrogate pairs) - the escaped flavor real providers
        // emit must decode to the same bytes as the raw-UTF-8 flavor
        std::string body;
        if (i % 5 == 3)
        {
            body = MakeEnvelopeEscaped(content, finish);
            ++escapedReplies;
        }
        else
        {
            body = MakeEnvelope(content, finish, /*pretty=*/(i % 7) == 3);
        }

        // the JSON path: byte-exact extraction or the stage fails
        CompletionEnvelope e = ParseCompletionEnvelope(body);
        bool ok = e.parsed && e.contentIsString && e.content == content &&
            e.finishLength == (finish[0] == 'l');
        if (!ok)
        {
            ++jsonFailures;
            std::printf("battery[%zu]: JSON extraction mismatch (finish=%s)\n", i, finish);
            continue;
        }
        if (!ContentUsable(e))
        {
            // battery content is clean by construction; a voicing-gate
            // rejection here would mean the gate is over-strict
            ++jsonFailures;
            std::printf("battery[%zu]: clean content rejected by the voicing gate\n", i);
            continue;
        }

        // the old regex path on the same body: document what it breaks
        std::string legacy = legacy_regex::Extract(body);
        if (legacy != content)
            ++regexFailures;
    }

    // the fixed unicode-escape envelope class rides in every battery run too
    {
        CompletionEnvelope e = ParseCompletionEnvelope(MakeUnicodeEscapeEnvelope());
        const std::string expected =
            "Aye, caf\xc3\xa9 \xe2\x80\x94 \xe4\xb8\xad\xe6\x96\x87 \xf0\x9f\x98\x80 done.";
        if (!(e.parsed && e.content == expected && ContentUsable(e)))
        {
            ++jsonFailures;
            std::printf("battery: unicode-escape envelope mismatch\n");
        }
        else if (legacy_regex::Extract(MakeUnicodeEscapeEnvelope()) != expected)
        {
            ++regexFailures;
        }
    }

    std::printf("{");
    std::printf("\"generations\": %zu, ", generations);
    std::printf("\"json_failures\": %zu, ", jsonFailures);
    std::printf("\"regex_failures\": %zu, ", regexFailures);
    std::printf("\"truncated\": %zu, ", truncatedSeen);
    std::printf("\"escaped\": %zu, ", escapedReplies);
    std::printf("\"tool_replies\": %zu", toolMarkerReplies);
    std::printf("}\n");
    std::printf("json battery: %zu/%zu zero-failure gate, regex baseline broke %zu\n",
                generations - jsonFailures, generations, regexFailures);
    if (jsonFailures)
    {
        // the A9 gate fails closed even when the binary is run outside
        // pytest - a bare exit 0 would let a regression ship silently
        ++g_failures;
    }
    else if (generations > 0)
        std::printf("json client battery passed\n");
}

// ---------------------------------------------------------------- fuzz -----
static void RunFuzz(size_t iterations)
{
    size_t parseOk = 0, spliceOk = 0, spliceRefused = 0, mutations = 0;
    std::vector<std::string> bodies;
    for (size_t i = 0; i < 24; ++i)
    {
        bodies.push_back(MakeEnvelope(MakeReplyContent(true),
            kFinishes[NextBelow(2)], (i % 5) == 2));
    }
    // depth-bomb corpus: the recursion cap must turn hostile nesting into a
    // clean parse failure, never a stack overflow
    {
        std::string deep = "{\"choices\":[{\"message\":{\"content\":\"x\",\"pad\":";
        for (size_t i = 0; i < 40; ++i)
            deep += "[";
        for (size_t i = 0; i < 40; ++i)
            deep += "]";
        deep += "}}]}";
        bodies.push_back(deep);
    }
    std::vector<std::string> requests;
    for (size_t i = 0; i < 8; ++i)
    {
        requests.push_back("{\"model\":\"local\",\"messages\":["
            "{\"role\":\"system\",\"content\":\"sys " + std::to_string(i) + "\"},"
            "{\"role\":\"user\",\"content\":\"user turn \\\"q\\\" " + std::to_string(i) + " \"}],"
            "\"max_tokens\":120,\"stream\":false}");
    }
    const std::string kInstruction = " Answer directly.";

    for (size_t it = 0; it < iterations; ++it)
    {
        std::string body = bodies[NextBelow(bodies.size())];
        // mutate 1-4 random bytes
        size_t cuts = 1 + NextBelow(4);
        for (size_t c = 0; c < cuts && !body.empty(); ++c)
        {
            ++mutations;
            size_t at = NextBelow(body.size());
            switch (NextBelow(4))
            {
                case 0: body[at] = (char)NextBelow(256); break;
                case 1: body.erase(at, 1); break;
                case 2: body.insert(at, 1, (char)('a' + NextBelow(26))); break;
                default: body[at] = "\"\\:[],{}"[NextBelow(9)]; break;
            }
        }
        // never crash, never hang: a mutated body either parses or not
        CompletionEnvelope e = ParseCompletionEnvelope(body);
        if (e.parsed)
        {
            ++parseOk;
            // a parsed mutation must still be self-consistent: the decoded
            // content re-escapes to the exact source span it claims
            detail::JsonValue root;
            if (detail::ParseJson(body, root))
            {
                const detail::JsonValue* ch = root.Find("choices");
                if (ch && !ch->items.empty())
                {
                    const detail::JsonValue* msg = ch->items[0].Find("message");
                    const detail::JsonValue* content = msg ? msg->Find("content") : nullptr;
                    if (content && content->type == detail::JSON_STRING)
                    {
                        std::string reencoded = "\"" + EscapeJsonString(content->str) + "\"";
                        // re-encoded need not be byte-identical (source may use
                        // different escapings) but MUST re-parse to the same value
                        // A12: the escaper now DROPS invalid UTF-8
                        // (the strict-UTF-8 request-side gate), so the
                        // round-trip preserves the SANITIZED value
                        detail::JsonValue check;
                        CHECK(detail::ParseJson(reencoded, check) &&
                              check.str == SanitizeUtf8(content->str),
                              "decoded content survives re-encode");
                    }
                }
            }
        }

        // splice fuzz: half the iterations use a PRISTINE request body so
        // the exact decoded result is checkable; half get one structural
        // mutation first (must never produce invalid JSON when accepted)
        std::string req = requests[NextBelow(requests.size())];
        bool pristine = (NextU64() & 1) != 0;
        if (!pristine && !req.empty())
            req[NextBelow(req.size())] = "\"\\:[],{}"[NextBelow(9)];
        std::string before = req;
        if (AppendInstructionToLastUserMessage(req, kInstruction))
        {
            ++spliceOk;
            detail::JsonValue root;
            CHECK(detail::ParseJson(req, root), "spliced body still parses");
            // must be a pure insertion: original bytes all present in order
            size_t pos = 0;
            bool subsequence = true;
            for (char c : before)
            {
                size_t at = req.find(c, pos);
                if (at == std::string::npos) { subsequence = false; break; }
                pos = at + 1;
            }
            CHECK(subsequence, "splice is a pure insertion");
            CHECK(req.size() == before.size() + kInstruction.size(),
                  "splice inserts exactly the instruction");
            if (pristine)
            {
                // on the pristine body the decoded last user content must
                // be the original turn plus exactly the instruction
                const detail::JsonValue* m = root.Find("messages");
                CHECK(m && !m->items.empty(), "pristine splice keeps messages");
                if (m && !m->items.empty())
                {
                    const detail::JsonValue* last = &m->items.back();
                    const detail::JsonValue* role = last->Find("role");
                    const detail::JsonValue* content = last->Find("content");
                    CHECK(role && role->type == detail::JSON_STRING &&
                          role->str == "user", "last message stays the user turn");
                    detail::JsonValue origRoot;
                    CHECK(detail::ParseJson(before, origRoot), "pristine baseline parses");
                    const detail::JsonValue* om = origRoot.Find("messages");
                    const detail::JsonValue* oc = om && !om->items.empty()
                        ? om->items.back().Find("content") : nullptr;
                    CHECK(content && content->type == detail::JSON_STRING &&
                          oc && oc->type == detail::JSON_STRING &&
                          content->str == oc->str + kInstruction,
                          "decoded last user content gained exactly the instruction");
                }
            }
        }
        else
        {
            ++spliceRefused;
            CHECK(req == before, "refused splice leaves the body untouched");
        }
    }

    std::printf("{");
    std::printf("\"iterations\": %zu, ", iterations);
    std::printf("\"mutations\": %zu, ", mutations);
    std::printf("\"parsed_ok\": %zu, ", parseOk);
    std::printf("\"splice_ok\": %zu, ", spliceOk);
    std::printf("\"splice_refused\": %zu", spliceRefused);
    std::printf("}\n");
    if (!g_failures)
        std::printf("json client fuzz passed\n");
}

int main(int argc, char** argv)
{
    const char* mode = argc > 1 ? argv[1] : "invariants";
    size_t n = argc > 2 ? (size_t)strtoul(argv[2], nullptr, 10) : 0;
    if (std::strcmp(mode, "battery") == 0)
        RunBattery(n ? n : 100);
    else if (std::strcmp(mode, "fuzz") == 0)
        RunFuzz(n ? n : 50000);
    else
        RunInvariants();
    if (g_failures)
        std::printf("%d failure(s)\n", g_failures);
    return g_failures ? 1 : 0;
}
