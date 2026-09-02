#include "PlayerbotLlmFilters.h"

#include "PlayerbotLlmBridge.h"
#include "PlayerbotLlmJson.h"
#include "PlayerbotLlmTruthCore.h"
#include "playerbot/PlayerbotAIConfig.h"

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <map>
#include <mutex>
#include <sstream>
#include <utility>
#include <vector>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#define POCKET_CLOSESOCKET(s) closesocket(s)
#else
#include <sys/socket.h>
#include <unistd.h>
#include <netinet/in.h>
#include <netdb.h>
#define POCKET_CLOSESOCKET(s) close(s)
#endif

namespace {

std::mutex& FilterMutex()
{
    static std::mutex instance;
    return instance;
}

// the bot's last 8 replies, per bot (the Jaccard reference set)
std::map<uint32, std::deque<std::string>>& ReplyRing()
{
    static std::map<uint32, std::deque<std::string>> instance;
    return instance;
}

// deflection rotation counter so a flooded bot varies its canned lines
std::map<uint32, uint64_t>& DeflectionCount()
{
    static std::map<uint32, uint64_t> instance;
    return instance;
}

// The instruction furniture of a request body's system message: the
// player-varying segments (backstory, relationship, absence, facts)
// drop, because re-voicing a remembered fact is recall, not leakage -
// the h4 law targets verbatim instruction/example recitation.
std::string LeakReferenceOf(std::string const& requestBody)
{
    using namespace pocketllm::detail;
    JsonValue root;
    if (!ParseJson(requestBody, root) || root.type != JSON_OBJECT)
        return std::string();
    JsonValue const* messages = root.Find("messages");
    if (!messages || messages->type != JSON_ARRAY || messages->items.empty())
        return std::string();
    JsonValue const* system = messages->items[0].Find("content");
    if (!system || system->type != JSON_STRING)
        return std::string();

    static char const* const varying[] = {
        "Backstory: ", "Relationship with ", "Facts you remember about ",
    };
    std::string out;
    std::istringstream stream(system->str);
    std::string line;
    while (std::getline(stream, line))
    {
        bool drop = false;
        for (char const* v : varying)
            if (line.compare(0, strlen(v), v) == 0)
                drop = true;
        // the two absence line shapes ("X was last seen ..." /
        // "You are meeting X for the first time.")
        if (line.find(" was last seen ") != std::string::npos ||
            line.compare(0, 18, "You are meeting ") == 0)
            drop = true;
        if (!drop)
            out += line + "\n";
    }
    return out;
}

// One plain-HTTP POST to the LLM endpoint (the /tokenize resolver; the
// embedded server is loopback HTTP, external endpoints never see the
// bias). Returns false on any failure - the caller fails open.
bool PostToLlmServer(std::string const& path, std::string const& body,
    std::string& responseOut)
{
    ParsedUrl const url = sPlayerbotAIConfig.llmEndPointUrl;
    if (url.hostname.empty())
        return false;

    char const* const host = url.hostname.c_str();
    std::string const port = std::to_string(url.port);

    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    struct addrinfo* res = nullptr;
    if (getaddrinfo(host, port.c_str(), &hints, &res) != 0 || !res)
        return false;

    int sock = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (sock < 0)
    {
        freeaddrinfo(res);
        return false;
    }
    // a short bounded wait: this runs once per process, off the reply path
#ifdef _WIN32
    DWORD const timeout = 3000;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, (char const*)&timeout, sizeof(timeout));
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, (char const*)&timeout, sizeof(timeout));
#else
    timeval const timeout = { 3, 0 };
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, (char const*)&timeout, sizeof(timeout));
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, (char const*)&timeout, sizeof(timeout));
#endif
    if (connect(sock, res->ai_addr, (int)res->ai_addrlen) != 0)
    {
        POCKET_CLOSESOCKET(sock);
        freeaddrinfo(res);
        return false;
    }
    freeaddrinfo(res);

    std::string request;
    request += "POST " + path + " HTTP/1.1\r\n";
    request += std::string("Host: ") + host + ":" + port + "\r\n";
    request += "Content-Type: application/json\r\n";
    request += "Content-Length: " + std::to_string(body.size()) + "\r\n";
    request += "Connection: close\r\n\r\n";
    request += body;

    size_t sent = 0;
    while (sent < request.size())
    {
        int const n = send(sock, request.data() + sent, (int)(request.size() - sent), 0);
        if (n <= 0)
        {
            POCKET_CLOSESOCKET(sock);
            return false;
        }
        sent += (size_t)n;
    }

    std::string raw;
    char buf[4096];
    while (raw.size() < 64 * 1024)
    {
        int const n = recv(sock, buf, sizeof(buf), 0);
        if (n <= 0)
            break;
        raw.append(buf, (size_t)n);
    }
    POCKET_CLOSESOCKET(sock);

    // split headers from the body (Connection: close ends the read)
    size_t const split = raw.find("\r\n\r\n");
    if (split == std::string::npos)
        return false;
    responseOut = raw.substr(split + 4);
    return !responseOut.empty();
}

// Extracts the first token array from a /tokenize response body.
std::vector<int> ParseTokenizeResponse(std::string const& body)
{
    using namespace pocketllm::detail;
    std::vector<int> out;
    JsonValue root;
    if (!ParseJson(body, root) || root.type != JSON_OBJECT)
        return out;
    if (JsonValue const* tokens = root.Find("tokens"))
        if (tokens->type == JSON_ARRAY)
            for (JsonValue const& t : tokens->items)
                if (t.type == JSON_NUMBER && !t.number.empty())
                    out.push_back(atoi(t.number.c_str()));
    return out;
}

bool IsKnownNameThunk(std::string const& name, void*)
{
    return PlayerbotLlmBridge::IsKnownName(name);
}

std::string ResolveEraBiasJson()
{
    if (!sPlayerbotAIConfig.llmEraBias)
        return std::string();
    if (sPlayerbotAIConfig.llmApiProviderSafe)
        return std::string(); // external endpoints: no llama bias keys
    // loopback only: the bias targets the EMBEDDED llama-server. A
    // hand-configured external endpoint must never receive the probe
    // traffic (round-1 R1); the app's external mode is providerSafe-
    // gated anyway, this covers the hand-conf edge
    {
        ParsedUrl const url = sPlayerbotAIConfig.llmEndPointUrl;
        if (url.hostname != "127.0.0.1" && url.hostname != "localhost")
            return std::string();
    }

    // both token cases per term: bare and leading-space forms
    std::vector<int> ids;
    for (std::string const& term : pocketllm::EraAlwaysBanTerms())
    {
        for (std::string const& form : { std::string(term), " " + term })
        {
            std::string const body = std::string("{\"content\":")
                + "\"" + pocketllm::EscapeJsonString(form) + "\"}";
            std::string response;
            if (!PostToLlmServer("/tokenize", body, response))
                return std::string(); // server unreachable: fail open
            std::vector<int> const tokens = ParseTokenizeResponse(response);
            if (tokens.empty())
                return std::string(); // not a /tokenize endpoint: fail open
            for (int id : tokens)
                if (std::find(ids.begin(), ids.end(), id) == ids.end())
                    ids.push_back(id);
        }
    }
    if (ids.empty())
        return std::string();
    std::string json = "{";
    for (size_t i = 0; i < ids.size(); ++i)
    {
        if (i)
            json += ",";
        json += std::to_string(ids[i]) + ":-50"; // plan A11: -20/-50 class
    }
    json += "}";
    return json;
}

} // namespace

std::string PlayerbotLlmFilters::HygienePass(std::string const& cleaned, uint32 botGuid)
{
    (void)botGuid;
    std::string out = pocketllm::StripMarkdown(cleaned);
    out = pocketllm::ClampAscii(out);

    // A10's post-filter leg: reply sentences that introduce an unknown
    // proper noun on a service/direction claim are stripped, not voiced
    std::vector<std::pair<size_t, size_t>> spans =
        pocketllm::InventionClaimSpans(out, IsKnownNameThunk, nullptr);
    for (size_t i = spans.size(); i-- > 0;)
        out.erase(spans[i].first, spans[i].second - spans[i].first);

    // trim the seams the drops leave behind
    size_t begin = out.find_first_not_of(" \t\r\n");
    if (begin == std::string::npos)
        return std::string();
    size_t end = out.find_last_not_of(" \t\r\n");
    return out.substr(begin, end - begin + 1);
}

bool PlayerbotLlmFilters::LeakFailureRaw(std::string const& raw,
    std::string const& requestBody)
{
    if (!pocketllm::EraScan(raw).empty())
        return true;
    std::string const reference = LeakReferenceOf(requestBody);
    if (reference.empty())
        return false;
    return pocketllm::SharesFiveGram(raw, reference);
}



bool PlayerbotLlmFilters::DuplicateOfRecent(uint32 botGuid,
    std::string const& cleaned)
{
    std::lock_guard<std::mutex> lock(FilterMutex());
    auto itr = ReplyRing().find(botGuid);
    if (itr == ReplyRing().end())
        return false;
    for (std::string const& prior : itr->second)
        if (pocketllm::JaccardWords(cleaned, prior) > 0.5)
            return true;
    return false;
}

void PlayerbotLlmFilters::RememberReply(uint32 botGuid, std::string const& cleaned)
{
    if (cleaned.empty())
        return;
    std::lock_guard<std::mutex> lock(FilterMutex());
    std::deque<std::string>& ring = ReplyRing()[botGuid];
    ring.push_back(cleaned);
    while (ring.size() > 8)
        ring.pop_front();
}

std::string PlayerbotLlmFilters::Deflection(uint32 botGuid)
{
    uint64_t salt;
    {
        std::lock_guard<std::mutex> lock(FilterMutex());
        salt = ++DeflectionCount()[botGuid];
    }
    return pocketllm::CannedDeflection(salt + botGuid);
}

std::string const& PlayerbotLlmFilters::EraBiasJson()
{
    static std::string cached;
    static bool resolved = false;
    static std::mutex mutex;
    std::lock_guard<std::mutex> lock(mutex);
    if (!resolved)
    {
        resolved = true;
        cached = ResolveEraBiasJson();
    }
    return cached;
}
