#ifndef _PlayerbotLlmFilters_h
#define _PlayerbotLlmFilters_h

/*
 * The A12 post-generation hygiene stack (plan §3), applied to the
 * CLEANED reply text (tool blocks already extracted and queued):
 *
 *   deterministic, every reply:  markdown stripper -> ASCII clamp ->
 *                                self-initiated-invention sentence drop
 *   regenerate-class (<=2 retries, then canned deflection):
 *                                5-gram prompt leak + marker terms +
 *                                era n-gram backstop
 *   one resample + tail note:    Jaccard > 0.5 vs the bot's last 8
 *                                replies (content-mandating notes are
 *                                exempt, rate-capped - see the bridge's
 *                                NoteMandatesContent)
 *
 * Plus the A11 era logit_bias: the always-ban terms resolved to token
 * ids through the embedded server's /tokenize endpoint, cached for the
 * process, spliced into request bodies by GenerateHttp (fail-open - no
 * /tokenize means no bias; the guard and the backstop still hold).
 *
 * Pure logic lives in PlayerbotLlmTruthCore.h (host-tested); this class
 * owns the stateful pieces (reply ring, bias cache, canned rotation).
 */
#include <cstdint>
#include <string>

class PlayerbotLlmFilters
{
public:
    // The deterministic passes, in order. Never fails: a filter problem
    // must cost polish, not the reply.
    static std::string HygienePass(std::string const& cleaned, uint32 botGuid);

    // True when the reply is leak-class (5-gram overlap with the request
    // body's instruction furniture, or an era backstop hit) and must be
    // regenerated. requestBody is the JSON body the generation answered.
    // This is the PRE-EXTRACTION form (5-gram + era only): the << >>
    // markers are legal in raw tool-bearing replies, so the marker-term
    // check runs SEPARATELY on the extracted text via
    // pocketllm::ContainsMarkerTerms.
    static bool LeakFailureRaw(std::string const& raw, std::string const& requestBody);

    // True when the reply is a near-duplicate (Jaccard > 0.5 on word
    // sets) of any of the bot's last 8 replies.
    static bool DuplicateOfRecent(uint32 botGuid, std::string const& cleaned);

    // Records a final (post-filter) reply into the 8-reply ring.
    static void RememberReply(uint32 botGuid, std::string const& cleaned);

    // The h4 last resort: a canned in-character deflection.
    static std::string Deflection(uint32 botGuid);

    // The era logit_bias body fragment ("{\"123\":-50,..."), resolved
    // once via /tokenize; EMPTY when unavailable (loopback offline,
    // providerSafe mode, /tokenize missing) - callers treat empty as
    // "splice nothing".
    static std::string const& EraBiasJson();
};

#endif
