#ifndef _PlayerbotLlmJson_h
#define _PlayerbotLlmJson_h

#include <cstddef>
#include <cstdio>
#include <string>
#include <utility>
#include <vector>

/*
 * The A9 real-JSON response client core (header-only, C++11, no external
 * dependencies).
 *
 * The shipped endpoints - the embedded llama-server and external
 * OpenAI-compatible services - answer /v1/chat/completions with a JSON
 * envelope. The old client regexed the assistant text out of the raw HTTP
 * body: the reviewed end pattern `(")` truncates at the first escaped quote
 * inside the reply, a newline in the reply breaks the start-pattern match,
 * and every non-ASCII escape is passed through mangled. This header parses
 * the envelope for real and decodes `choices[0].message.content` with full
 * JSON string semantics (escaped quotes, \n/\t, \uXXXX incl. surrogate
 * pairs).
 *
 * The regex path stays as the fallback for endpoints that return non-OpenAI
 * text shapes: bodies that do not parse as JSON are returned untouched by
 * the caller and flow through the conf-level patterns unchanged.
 *
 * Extracted as a pure header so the host battery
 * (tools/test_llm_json_client.cpp, run by tests/test_llm_json_client.py)
 * compiles the exact shipped code with -std=c++11, like llm_banter_core.h.
 * (Size note: the plan sanctioned "a ~200-line parser" - the parser core
 * in namespace detail is ~350 lines; the rest is the envelope/retry-splice/
 * trim/voicing-gate API the same A9 paragraph requires. Recorded as a
 * deliberate decision, not silent drift.)
 */
namespace pocketllm
{
namespace detail
{

enum JsonType
{
    JSON_NULL,
    JSON_BOOL,
    JSON_NUMBER,
    JSON_STRING,
    JSON_ARRAY,
    JSON_OBJECT
};

struct JsonValue
{
    JsonType type = JSON_NULL;
    bool boolean = false;
    std::string number;   // raw number text, uninterpreted
    std::string str;      // decoded string value (strings only)
    size_t rawEnd = 0;    // source offset of this string's closing quote -
                          // the safe splice point for appending inside it
    std::vector<JsonValue> items;                        // array
    std::vector<std::pair<std::string, JsonValue>> members;  // object

    const JsonValue* Find(const char* key) const
    {
        if (type != JSON_OBJECT)
            return nullptr;
        for (auto const& member : members)
            if (member.first == key)
                return &member.second;
        return nullptr;
    }
};

// Parsing limits: real envelopes are shallow and small; the caps turn a
// hostile or corrupted body into a clean parse failure (fallback path)
// instead of unbounded recursion or allocation. The value cap is charged
// at BOTH the container and its first entry, so it bounds leaves slightly
// below the nominal count - it is a safety bound, not a quota.
static const size_t kMaxDepth = 24;
static const size_t kMaxValueCount = 200000;

inline void AppendUtf8(std::string& out, unsigned int cp)
{
    if (cp < 0x80)
        out += static_cast<char>(cp);
    else if (cp < 0x800)
    {
        out += static_cast<char>(0xC0 | (cp >> 6));
        out += static_cast<char>(0x80 | (cp & 0x3F));
    }
    else if (cp < 0x10000)
    {
        out += static_cast<char>(0xE0 | (cp >> 12));
        out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
        out += static_cast<char>(0x80 | (cp & 0x3F));
    }
    else
    {
        out += static_cast<char>(0xF0 | (cp >> 18));
        out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
        out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
        out += static_cast<char>(0x80 | (cp & 0x3F));
    }
}

struct JsonCursor
{
    std::string const* text = nullptr;
    size_t pos = 0;
    size_t count = 0;
    bool failed = false;

    bool Reserve()
    {
        if (++count > kMaxValueCount)
            return Fail();
        return true;
    }

    bool Fail()
    {
        failed = true;
        return false;
    }

    void SkipWs()
    {
        while (pos < text->size())
        {
            char c = (*text)[pos];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r')
                ++pos;
            else
                break;
        }
    }

    bool Literal(const char* word)
    {
        size_t n = 0;
        while (word[n])
        {
            if (pos + n >= text->size() || (*text)[pos + n] != word[n])
                return Fail();
            ++n;
        }
        pos += n;
        return true;
    }

    // Parses a JSON string starting at the opening quote. Strict on raw
    // control characters and invalid escapes (a real provider envelope
    // never contains either; anything else belongs on the fallback path).
    // A lone surrogate degrades to U+FFFD rather than failing the whole
    // document - some providers emit them and the reply must survive.
    bool ParseString(std::string& out, size_t& closeQuote)
    {
        if (pos >= text->size() || (*text)[pos] != '"')
            return Fail();
        ++pos;
        out.clear();
        while (true)
        {
            if (pos >= text->size())
                return Fail();
            unsigned char c = static_cast<unsigned char>((*text)[pos]);
            if (c == '"')
            {
                closeQuote = pos;
                ++pos;
                return true;
            }
            if (c < 0x20)
                return Fail();
            if (c == '\\')
            {
                ++pos;
                if (pos >= text->size())
                    return Fail();
                char e = (*text)[pos];
                switch (e)
                {
                    case '"': out += '"'; ++pos; break;
                    case '\\': out += '\\'; ++pos; break;
                    case '/': out += '/'; ++pos; break;
                    case 'b': out += '\b'; ++pos; break;
                    case 'f': out += '\f'; ++pos; break;
                    case 'n': out += '\n'; ++pos; break;
                    case 'r': out += '\r'; ++pos; break;
                    case 't': out += '\t'; ++pos; break;
                    case 'u':
                    {
                        ++pos;
                        unsigned int cp = 0;
                        if (!ParseHex4(cp))
                            return Fail();
                        if (cp >= 0xD800 && cp <= 0xDBFF && pos + 1 < text->size() &&
                            (*text)[pos] == '\\' && (*text)[pos + 1] == 'u')
                        {
                            size_t save = pos;
                            pos += 2;
                            unsigned int lo = 0;
                            if (ParseHex4(lo) && lo >= 0xDC00 && lo <= 0xDFFF)
                                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                            else
                            {
                                // not a surrogate pair: emit U+FFFD for the
                                // lone high surrogate and re-parse what
                                // follows as its own escape/plain char
                                pos = save;
                                AppendUtf8(out, 0xFFFD);
                                break;
                            }
                        }
                        else if (cp >= 0xD800 && cp <= 0xDFFF)
                        {
                            AppendUtf8(out, 0xFFFD);
                            break;
                        }
                        AppendUtf8(out, cp);
                        break;
                    }
                    default:
                        return Fail();
                }
                continue;
            }
            out += static_cast<char>(c);
            ++pos;
        }
    }

    bool ParseHex4(unsigned int& out)
    {
        out = 0;
        if (pos + 4 > text->size())
            return Fail();
        for (int i = 0; i < 4; ++i)
        {
            char h = (*text)[pos + i];
            out <<= 4;
            if (h >= '0' && h <= '9')
                out += static_cast<unsigned int>(h - '0');
            else if (h >= 'a' && h <= 'f')
                out += static_cast<unsigned int>(h - 'a' + 10);
            else if (h >= 'A' && h <= 'F')
                out += static_cast<unsigned int>(h - 'A' + 10);
            else
                return Fail();
        }
        pos += 4;
        return true;
    }

    bool ParseValue(JsonValue& out, size_t depth);
    bool ParseNumber(JsonValue& out);
    bool ParseArray(JsonValue& out, size_t depth);
    bool ParseObject(JsonValue& out, size_t depth);
};

inline bool JsonCursor::ParseNumber(JsonValue& out)
{
    size_t start = pos;
    if (pos < text->size() && (*text)[pos] == '-')
        ++pos;
    size_t intDigits = 0;
    while (pos < text->size() && (*text)[pos] >= '0' && (*text)[pos] <= '9')
    {
        ++pos;
        ++intDigits;
    }
    if (intDigits == 0)
        return Fail();
    if (pos < text->size() && (*text)[pos] == '.')
    {
        ++pos;
        size_t fracDigits = 0;
        while (pos < text->size() && (*text)[pos] >= '0' && (*text)[pos] <= '9')
        {
            ++pos;
            ++fracDigits;
        }
        if (fracDigits == 0)
            return Fail();
    }
    if (pos < text->size() && ((*text)[pos] == 'e' || (*text)[pos] == 'E'))
    {
        ++pos;
        if (pos < text->size() && ((*text)[pos] == '+' || (*text)[pos] == '-'))
            ++pos;
        size_t expDigits = 0;
        while (pos < text->size() && (*text)[pos] >= '0' && (*text)[pos] <= '9')
        {
            ++pos;
            ++expDigits;
        }
        if (expDigits == 0)
            return Fail();
    }
    out.type = JSON_NUMBER;
    out.number = text->substr(start, pos - start);
    return true;
}

inline bool JsonCursor::ParseArray(JsonValue& out, size_t depth)
{
    if (!Reserve() || depth > kMaxDepth)
        return Fail();
    // caller consumed '['
    out.type = JSON_ARRAY;
    SkipWs();
    if (pos < text->size() && (*text)[pos] == ']')
    {
        ++pos;
        return true;
    }
    while (true)
    {
        JsonValue item;
        SkipWs();
        if (!ParseValue(item, depth + 1))
            return Fail();
        out.items.push_back(std::move(item));
        SkipWs();
        if (pos >= text->size())
            return Fail();
        if ((*text)[pos] == ',')
        {
            ++pos;
            continue;
        }
        if ((*text)[pos] == ']')
        {
            ++pos;
            return true;
        }
        return Fail();
    }
}

inline bool JsonCursor::ParseObject(JsonValue& out, size_t depth)
{
    if (!Reserve() || depth > kMaxDepth)
        return Fail();
    // caller consumed '{'
    out.type = JSON_OBJECT;
    SkipWs();
    if (pos < text->size() && (*text)[pos] == '}')
    {
        ++pos;
        return true;
    }
    while (true)
    {
        SkipWs();
        std::string key;
        size_t keyClose = 0;
        if (!ParseString(key, keyClose))
            return Fail();
        SkipWs();
        if (pos >= text->size() || (*text)[pos] != ':')
            return Fail();
        ++pos;
        SkipWs();
        JsonValue value;
        if (!ParseValue(value, depth + 1))
            return Fail();
        out.members.push_back(std::make_pair(std::move(key), std::move(value)));
        SkipWs();
        if (pos >= text->size())
            return Fail();
        if ((*text)[pos] == ',')
        {
            ++pos;
            continue;
        }
        if ((*text)[pos] == '}')
        {
            ++pos;
            return true;
        }
        return Fail();
    }
}

inline bool JsonCursor::ParseValue(JsonValue& out, size_t depth)
{
    if (!Reserve() || depth > kMaxDepth)
        return Fail();
    SkipWs();
    if (pos >= text->size())
        return Fail();
    char c = (*text)[pos];
    if (c == '{')
    {
        ++pos;
        return ParseObject(out, depth);
    }
    if (c == '[')
    {
        ++pos;
        return ParseArray(out, depth);
    }
    if (c == '"')
    {
        size_t closeQuote = 0;
        if (!ParseString(out.str, closeQuote))
            return Fail();
        out.type = JSON_STRING;
        out.rawEnd = closeQuote;
        return true;
    }
    if (c == 't')
    {
        if (!Literal("true"))
            return false;
        out.type = JSON_BOOL;
        out.boolean = true;
        return true;
    }
    if (c == 'f')
    {
        if (!Literal("false"))
            return false;
        out.type = JSON_BOOL;
        out.boolean = false;
        return true;
    }
    if (c == 'n')
    {
        if (!Literal("null"))
            return false;
        out.type = JSON_NULL;
        return true;
    }
    return ParseNumber(out);
}

// Full-document parse: exactly one top-level value, only whitespace may
// follow. Any violation fails (the caller then keeps the body on the
// legacy regex fallback path instead of guessing at a half-parsed reply).
inline bool ParseJson(const std::string& text, JsonValue& out)
{
    if (text.size() > 64u * 1024u * 1024u)
        return false;
    JsonCursor cursor;
    cursor.text = &text;
    if (!cursor.ParseValue(out, 0) || cursor.failed)
        return false;
    cursor.SkipWs();
    return cursor.pos == text.size();
}

} // namespace detail

/**
 * The OpenAI chat-completions envelope, extracted from an HTTP response
 * body. `parsed` is true only for a shape the client actually knows how to
 * voice (choices[0].message object, or the legacy completions
 * choices[0].text string); anything else - non-JSON bodies included - is
 * left to the caller's fallback policy. `jsonParsable` records whether the
 * body was a valid JSON document at all so the caller can distinguish
 * "error envelope, fail quiet" from "not JSON, regex fallback" without a
 * second parse pass.
 */
struct CompletionEnvelope
{
    bool parsed = false;
    bool jsonParsable = false;     // body was a valid JSON document
    bool contentIsString = false;  // content was a JSON string (null is not)
    std::string content;           // decoded assistant text
    bool reasoningPresent = false; // non-empty reasoning_content (the §1.4
                                   // thinking-preamble symptom)
    bool finishLength = false;     // finish_reason == "length"
};

/**
 * Voicing gate for decoded content. Beyond non-empty, the text must be
 * speakable by the 1.12 chat path: no C0 control bytes other than
 * \n/\r/\t (an embedded NUL would truncate the packet stream mid-reply),
 * no DEL or C1 controls, and STRICT well-formed UTF-8 - the per-lead
 * continuation ranges are enforced (overlong encodings, surrogate halves
 * and anything beyond U+10FFFF render as client garbage). Bounded above
 * at 32 KiB: a real reply is capped by the request's max_tokens, so a
 * megabyte string is a hostile bomb, never prose. Fails closed:
 * unusable content is the caller's fail-quiet case, never a partial voice.
 */
inline bool ContentUsable(const CompletionEnvelope& e)
{
    if (!e.parsed || !e.contentIsString)
        return false;
    if (e.content.size() > 32768)
        return false;
    bool sawPrintable = false;
    unsigned int continuation = 0;
    // the FIRST continuation byte after certain leads is range-restricted
    // (overlongs, surrogates, > U+10FFFF); later continuations accept the
    // full 0x80-0xBF span
    unsigned char minNext = 0x80, maxNext = 0xBF;
    for (size_t i = 0; i < e.content.size(); ++i)
    {
        unsigned char c = static_cast<unsigned char>(e.content[i]);
        if (c < 0x20 && c != '\n' && c != '\r' && c != '\t')
            return false;
        if (c == 0x7F)
            return false;                 // DEL
        if (c < 0x80)
        {
            if (continuation)
                return false;             // ASCII inside a multibyte sequence
            if (c > 0x20)
                sawPrintable = true;
            continue;
        }
        if (continuation)
        {
            if (c < minNext || c > maxNext)
                return false;             // malformed or range-invalid sequence
            minNext = 0x80;
            maxNext = 0xBF;
            --continuation;
            continue;
        }
        if ((c & 0xE0) == 0xC0)
        {
            if (c < 0xC2)
                return false;             // overlong 2-byte encoding
            continuation = 1;
            if (c == 0xC2)
                minNext = 0xA0;           // C2 80-9F = C1 controls (NEL, ...)
        }
        else if ((c & 0xF0) == 0xE0)
        {
            continuation = 2;
            if (c == 0xE0)
                minNext = 0xA0;           // E0 80-9F = overlong 3-byte
            else if (c == 0xED)
                maxNext = 0x9F;           // ED A0-BF = surrogate halves
        }
        else if ((c & 0xF8) == 0xF0)
        {
            if (c > 0xF4)
                return false;             // beyond any lead value
            continuation = 3;
            if (c == 0xF0)
                minNext = 0x90;           // F0 80-8F = overlong 4-byte
            else if (c == 0xF4)
                maxNext = 0x8F;           // F4 90+ = beyond U+10FFFF
        }
        else
            return false;                 // bare continuation byte
        sawPrintable = true;
    }
    return continuation == 0 && sawPrintable;
}

/**
 * True when the body is any valid JSON document. The client distinguishes
 * this from ParseCompletionEnvelope's `parsed`: a body that is JSON but
 * carries no completion (an `{"error":...}` envelope from the server, or an
 * unexpected shape) must fail quiet - it must never be voiced raw - while a
 * body that is not JSON at all belongs on the legacy regex fallback path
 * for endpoints that return plain text shapes.
 */
inline bool ParsesAsJson(const std::string& body)
{
    detail::JsonValue root;
    return detail::ParseJson(body, root);
}

inline CompletionEnvelope ParseCompletionEnvelope(const std::string& body)
{
    CompletionEnvelope e;
    detail::JsonValue root;
    if (!detail::ParseJson(body, root))
        return e;
    e.jsonParsable = true;
    if (root.type != detail::JSON_OBJECT)
        return e;
    const detail::JsonValue* choices = root.Find("choices");
    if (!choices || choices->type != detail::JSON_ARRAY || choices->items.empty())
        return e;
    const detail::JsonValue& choice = choices->items[0];
    if (choice.type != detail::JSON_OBJECT)
        return e;

    const detail::JsonValue* message = choice.Find("message");
    const detail::JsonValue* content = nullptr;
    if (message && message->type == detail::JSON_OBJECT)
    {
        content = message->Find("content");
        if (const detail::JsonValue* reasoning = message->Find("reasoning_content"))
            if (reasoning->type == detail::JSON_STRING && !reasoning->str.empty())
                e.reasoningPresent = true;
    }
    else
    {
        // legacy /v1/completions shape: the text rides choices[0].text
        content = choice.Find("text");
    }
    if (!content)
        return e;

    e.parsed = true;
    if (content->type == detail::JSON_STRING)
    {
        e.contentIsString = true;
        e.content = content->str;
    }
    if (const detail::JsonValue* finish = choice.Find("finish_reason"))
        if (finish->type == detail::JSON_STRING && finish->str == "length")
            e.finishLength = true;
    return e;
}

/** JSON-escape `value` for embedding inside a JSON string literal. */
// A12 strict-UTF-8 sanitizer: drops INVALID sequences only (a lead
// byte without its continuations, an orphaned continuation) and keeps
// valid 1-4 byte sequences whole. The request-side gate - a player line
// with stray Latin-1 bytes would build a body the server rejects
// outright, a silent dead generation.
inline std::string SanitizeUtf8(const std::string& text)
{
    std::string out;
    out.reserve(text.size());
    for (size_t i = 0; i < text.size();)
    {
        unsigned char const c = static_cast<unsigned char>(text[i]);
        size_t len = 0;
        if (c < 0x80)
            len = 1;
        else if ((c & 0xE0) == 0xC0)
            len = 2;
        else if ((c & 0xF0) == 0xE0)
            len = 3;
        else if ((c & 0xF8) == 0xF0)
            len = 4;
        bool valid = len > 0 && i + len <= text.size();
        for (size_t k = 1; valid && k < len; ++k)
            valid = (static_cast<unsigned char>(text[i + k]) & 0xC0) == 0x80;
        if (valid)
        {
            out.append(text, i, len);
            i += len;
        }
        else
            ++i; // drop the invalid byte
    }
    return out;
}

inline std::string EscapeJsonString(const std::string& value)
{
    std::string out;
    out.reserve(value.size());
    for (size_t i = 0; i < value.size(); ++i)
    {
        unsigned char c = static_cast<unsigned char>(value[i]);
        // A12 strict-UTF-8 request-side gate (the S4 R6 finding, folded
        // here so every request string passes it at the single choke
        // point): a player line with stray Latin-1 bytes would build a
        // body the server rejects outright - a silent dead generation.
        // Valid multibyte sequences pass through whole (their bytes are
        // all >= 0x80 and none are escaped below); INVALID sequences
        // (lead without continuation, orphaned continuation) drop.
        if (c >= 0xC0)
        {
            // same classification as SanitizeUtf8 (0xF8+ is never a
            // lead; 0xC0/0xC1 are structurally 2-byte leads and pass
            // whole when their continuation byte follows - overlong
            // forms included, exactly as in SanitizeUtf8; it is the
            // reply-side gate, ContentUsable, that rejects them)
            size_t const len = (c & 0xE0) == 0xC0 ? 2 :
                (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 0;
            bool valid = len > 0 && i + len <= value.size();
            for (size_t k = 1; valid && k < len; ++k)
                valid = (static_cast<unsigned char>(value[i + k]) & 0xC0) == 0x80;
            if (valid)
            {
                out.append(value, i, len);
                i += len - 1;
            }
            continue; // valid sequence emitted, or invalid lead dropped
        }
        if (c >= 0x80)
            continue; // orphaned continuation byte: drop
        switch (c)
        {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20)
                {
                    char buffer[8];
                    std::snprintf(buffer, sizeof(buffer), "\\u%04x", c);
                    out += buffer;
                }
                else
                {
                    out += static_cast<char>(c);
                }
        }
    }
    return out;
}

/**
 * Plausibility gate for the non-JSON fallback path. A body that is not
 * JSON may still be a plain-text completion from a hand-configured
 * endpoint - but error pages, BOM-prefixed or broken JSON envelopes and
 * binary garbage must never reach the chat lines (with the conf patterns
 * emptied, nothing else would filter them). Prose-shaped text passes:
 * letters, punctuation, bracketed emote lines, quotes. Bounded above at
 * 32 KiB - a real reply is capped by the request's max_tokens, so a
 * multi-megabyte body is a flood attempt, never prose.
 */
inline bool LooksLikeVoicableText(const std::string& body)
{
    if (body.size() > 32768)
        return false;                     // never voice a bomb
    if (body.compare(0, 3, "\xEF\xBB\xBF") == 0)
        return false;                     // BOM-prefixed envelope remainder
    size_t first = body.find_first_not_of(" \t\r\n");
    if (first == std::string::npos || body.size() - first < 4)
        return false;                     // a whisper reply is never < 4 chars
    unsigned char lead = static_cast<unsigned char>(body[first]);
    if (lead == '<' || lead == '{')
        return false;                     // HTML/XML page or JSON envelope remnant
    if (lead == '}' || lead == ']')
        return false;                     // envelope-tail fragment (lost its head)
    if (lead == ',' || lead == ':')
        return false;                     // JSON structural separators - same tail class
    if (lead == '[')
    {
        // a legitimate emote header closes within 80 chars and is followed
        // by prose; structural garbage (`[[[[...`, cap-defeated arrays)
        // has neither
        size_t close = body.find(']', first);
        if (close == std::string::npos || close - first > 80)
            return false;
    }
    if (lead == '"')
    {
        // a leading `"key":` fragment is a broken JSON envelope (it lost
        // its opening brace), never a prose reply
        size_t colon = body.find("\":", first);
        if (colon != std::string::npos && colon - first <= 64)
            return false;
    }
    size_t controls = 0;
    size_t total = 0;
    size_t letterRun = 0;
    size_t bestLetterRun = 0;
    for (size_t i = first; i < body.size(); ++i)
    {
        unsigned char c = static_cast<unsigned char>(body[i]);
        if (c == 0)
            return false;
        if (c == 0x7F)
            return false;                 // DEL - refused on the decoded path too
        if (c < 0x20 && c != '\n' && c != '\r' && c != '\t')
            ++controls;
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))
        {
            if (++letterRun > bestLetterRun)
                bestLetterRun = letterRun;
        }
        else
            letterRun = 0;
        ++total;
    }
    if (controls * 20 >= total)
        return false;                     // >= 5% control bytes
    // prose has words: at least one run of three consecutive ASCII letters
    // (the 1.12 client is Latin-centric; non-Latin plain-text endpoints
    // fail quiet here by design)
    return bestLetterRun >= 3;
}

/**
 * Splice `instruction` onto the end of the last user message's content
 * string, in place, preserving every other byte of the request body
 * (numbers, key order, provider-specific fields). Returns false - leaving
 * the body untouched - when there is no user turn, or when the LAST user
 * turn's content is not a plain string (amending an earlier turn instead
 * would put the instruction on the wrong turn, so the whole splice is
 * refused); the caller then gives up quiet rather than guessing.
 *
 * Used for the §1.4 empty-content retry: the pinned base model spends the
 * whole budget on a thinking preamble routed into reasoning_content, so the
 * client resends once with a direct-answer instruction appended to the turn
 * being answered. The insertion is a pure byte splice at the closing quote:
 * the server's KV prefix cache stays valid for every token before it.
 */
inline bool AppendInstructionToLastUserMessage(std::string& body,
    const std::string& instruction)
{
    detail::JsonValue root;
    if (!detail::ParseJson(body, root) || root.type != detail::JSON_OBJECT)
        return false;
    const detail::JsonValue* messages = root.Find("messages");
    if (!messages || messages->type != detail::JSON_ARRAY)
        return false;
    for (auto it = messages->items.rbegin(); it != messages->items.rend(); ++it)
    {
        if (it->type != detail::JSON_OBJECT)
            continue;
        const detail::JsonValue* role = it->Find("role");
        if (!role || role->type != detail::JSON_STRING || role->str != "user")
            continue;
        const detail::JsonValue* content = it->Find("content");
        if (!content || content->type != detail::JSON_STRING)
            return false;                 // last user turn is not amendable
        // rawEnd is the closing quote's offset: inserting the escaped
        // instruction there appends INSIDE the string without touching
        // any other byte of the original body.
        body.insert(content->rawEnd, EscapeJsonString(instruction));
        return true;
    }
    return false;
}

/**
 * A finish_reason=="length" generation was cut mid-stream. Drop the
 * dangling partial sentence so the line splitter does not voice a fragment
 * that ends mid-word: everything up to and including the last sentence
 * terminator is kept. A reply with no terminator at all is passed through
 * unchanged - it has no dangling tail to remove, and silence is worse than
 * a complete clause (the empty-content guard above already catches the
 * burn-the-whole-budget-on-thinking variant, which is the common
 * no-terminator case). The cut is byte-oriented: ASCII terminators cannot
 * appear inside a UTF-8 multibyte sequence.
 */
inline std::string TrimTruncatedTail(const std::string& text)
{
    for (size_t i = text.size(); i > 0; --i)
    {
        char c = text[i - 1];
        if (c == '.' || c == '!' || c == '?')
        {
            std::string kept = text.substr(0, i);
            while (!kept.empty() &&
                (kept.back() == ' ' || kept.back() == '\n' ||
                 kept.back() == '\r' || kept.back() == '\t'))
                kept.pop_back();
            return kept;
        }
    }
    return text;
}

} // namespace pocketllm

#endif
