"""A5: the murmur floor failure-fallback latch (cloud-scoped) - host pins.

The composer-configured rungs can never reach the authored floor today
(the manual-override floor branch requires generated=false, which no
live rung sets) - a dead cloud endpoint meant pure silence on the murmur
lane. This battery pins the latch + post-failure floor leg added to
PlayerbotLlmChatter.cpp:
  - the {lane, class, time} failure latch is a set of atomics in
    ChatterState, written ONLY by the composer worker's hard-failure
    site (PostChatHttp == "error"; busy/empty never latch), with a
    consecutive-failure streak that doubles the floor's effective
    spacing capped at 8x and resets on a usable batch
  - the refill tick READS-AND-CLEARS the latch and expires it after
    10 minutes
  - the post-failure floor leg is gated on CloudLaneOpen() (the key AND
    tier conjunction - never the bare key), keeps the floor's own
    enqueue guard set and rides the normal delivery stamp
  - the dormant manual-override floor branch stays BYTE-IDENTICAL
    (pinned as an exact source slice) and the device-lane silence
    doctrine pin survives
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHATTER_CPP = ROOT / "native" / "patches" / "playerbots" / "PlayerbotLlmChatter.cpp"
MEMORY_H = ROOT / "native" / "patches" / "playerbots" / "PlayerbotLlmMemory.h"

# the manual-override floor leg, pinned BYTE-EXACT: any semantic edit to
# the dormant lane (or an accidental reindent) fails here
MANUAL_OVERRIDE_FLOOR_SLICE = '''                else
                {
                    // authored event-grounded floor. DORMANT under the
                    // collapsed ladder: every live rung generates (the
                    // dim row stretches cadence rather than dropping to
                    // templates), so this branch only runs if a future
                    // policy row sets generated=false on a live rung -
                    // kept as that manual-override lane. The template
                    // draw is fatigue-spaced per (template x speaker x
                    // listener) - the authored law. The line-safety law
                    // covers the floor too: the DB event text can carry
                    // pipes/newlines past the write chain (model-authored
                    // share_gossip rows) - fail silent, never voice them.
                    size_t const tpl = urand(0, uint32(pocketllm::MurmurFloorTemplateCount() - 1));
                    std::string const floorText = pocketllm::ClampMurmurBytes(
                        pocketllm::RenderFloorTemplate(tpl, speaker->GetName(),
                            listener->GetName(), telling),
                        pocketllm::kMurmurMaxBytes);
                    if (pocketllm::ChatterLineSafe(floorText))
                    {
                        std::lock_guard<std::mutex> lock(s.mutex);
                        if (now - s.lastFloorAt >= policy.floorMinSpacingSec &&
                            pocketllm::TemplateSpacingAdmits(s.fatigue, tpl,
                                speaker->GetGUIDLow(), listener->GetGUIDLow(),
                                now, policy.floorMinSpacingSec) &&
                            s.queue.size() < kQueueCap)
                        {
                            PendingLine entry;
                            entry.layer = pocketllm::LAYER_MURMUR;
                            entry.floor = true;
                            entry.speakerGuid = speaker->GetGUIDLow();
                            entry.listenerGuid = listener->GetGUIDLow();
                            entry.text = floorText;
                            entry.factKey = row.factKey;
                            entry.originator = row.originatorForSpeaker;
                            entry.templateIdx = tpl;
                            entry.notBefore = now + urand(policy.murmurDisplayMinSec,
                                policy.murmurDisplayMaxSec);
                            if (pocketllm::RingAdmits(s.ring, entry.text))
                            {
                                s.queue.push_back(entry);
                                s.lastFloorAt = now;
                            }
                        }
                    }
                }
'''


def src() -> str:
    return CHATTER_CPP.read_text(encoding="utf-8")


# ---- the latch ----------------------------------------------------------------

def composer_worker_slice(text: str) -> str:
    """RunComposerBatchInner's DEFINITION body (skipping the forward
    declaration earlier in the file)."""
    return text.split("void RunComposerBatchInner(ComposerJob const& job)\n{")[1].split(
        "\nPersonaDims DimsOf")[0]


def device_worker_slice(text: str) -> str:
    """RunDeviceBatchInner's definition body."""
    return text.split("void RunDeviceBatchInner(DeviceJob const& job)\n{")[1].split(
        "\n// the composer worker")[0]


def test_latch_fields_live_in_chatter_state_as_atomics():
    text = src()
    state = text.split("struct ChatterState")[1].split("\n};")[0]
    for field in ("failLane", "failClass", "failAt", "failStreak"):
        assert re.search(rf"std::atomic<\w+> {field}\{{0\}};", state), \
            f"{field} is a zero-initialized atomic in ChatterState"


def test_latch_written_only_by_the_composer_worker_on_the_error_class():
    text = src()
    composer = composer_worker_slice(text)
    # the ONLY write site: guarded on the hard class exactly
    assert 'if (http == "error")' in composer
    write = composer.split('if (http == "error")')[1].split("pocketllm::CompletionEnvelope")[0]
    assert "State().failLane.store((uint32)job.layer);" in write
    assert "State().failAt.store(time(nullptr));" in write
    # class: timeout is distinguished from the plain error class via the
    # thread-local gen-class note
    assert 'pocketllm::GenClassNote() == "timeout"' in write
    assert "kFailClassTimeout" in write and "kFailClassError" in write
    # the streak grows per consecutive failure, capped by the shift cap
    assert "if (streak < kFailStreakCapShift)" in write
    # a usable batch resets the streak (consecutive semantics)
    assert "State().failStreak.store(0);" in composer

    # the DEVICE worker never writes the latch (device lane never feeds
    # the cloud floor)
    device = device_worker_slice(text)
    for banned in ("failLane.store", "failClass.store", "failAt.store",
                   "failStreak.store"):
        assert banned not in device, f"device worker must never write {banned}"
    # and the file-wide truth: every latch write lives in the composer
    # worker slice
    for whole in ("failLane.store", "failClass.store", "failAt.store"):
        assert text.count(whole) == 1, f"{whole} appears exactly once (composer worker)"
    assert text.count("failStreak.store") == 2  # bump + success reset, both composer


def test_busy_never_latches():
    text = src()
    composer = composer_worker_slice(text)
    # the write is keyed on the literal "error" return only - the busy
    # placeholder (governor denial) and an empty-but-parsed body return
    # different strings and never latch
    assert 'http == "error"' in composer
    assert composer.index('if (http == "error")') < composer.index("ParseCompletionEnvelope(http)")


def test_read_and_clear_at_the_refill_tick_with_expiry():
    text = src()
    refill = text.split("if (quiet && murmurQueued < policy.murmurQueueLowWater")[1].split(
        "for (Player* player : players)")[0]
    # read-AND-clear in one atomic step (an ordinary .load() read would
    # re-arm the floor every tick off one failure)
    assert "s.failAt.exchange(0)" in refill
    # the 10-minute expiry: an older latch is dropped, not armed
    assert "kFailLatchExpirySec" in refill
    assert "now - failAtV <= kFailLatchExpirySec" in refill
    # the class must be a real failure class to arm
    assert "s.failClass.load() != kFailClassNone" in refill
    # the streak cap constant exists with the 8x value
    assert "kFailStreakCapShift = 3" in text
    assert "kFailLatchExpirySec = 600" in text


def test_spacing_doubling_capped_at_8x():
    text = src()
    refill = text.split("if (quiet && murmurQueued < policy.murmurQueueLowWater")[1].split(
        "for (Player* player : players)")[0]
    assert "std::max<uint32>(\n                        policy.floorMinSpacingSec, kCloudFloorBaseSec) <<" in refill
    assert "std::min(s.failStreak.load(), kFailStreakCapShift)" in refill
    # the real A5 base spacing (floorMinSpacingSec is 0 on every live
    # rung today - the plan stages >= 270)
    assert "kCloudFloorBaseSec = 270" in text
    # the doubling table implied by the real constants, as a host check
    base, cap_shift = 270, 3
    table = [base << min(s, cap_shift) for s in range(8)]
    assert table == [270, 540, 1080, 2160, 2160, 2160, 2160, 2160], \
        "spacing doubles per consecutive failed batch, capped at 8x"


# ---- the post-failure floor leg -------------------------------------------------

def test_post_failure_floor_gated_on_the_conjunction():
    text = src()
    assert ("cloudFloorArmed = s.failClass.load() != kFailClassNone &&\n"
            "                        CloudLaneOpen();" in text), \
        "the floor arm consumes CloudLaneOpen() (key AND tier), never the bare key"
    # chatter reaches the conjunction through the memory header
    assert '#include "PlayerbotLlmMemory.h"' in text
    header = MEMORY_H.read_text(encoding="utf-8")
    assert "inline bool CloudLaneOpen()" in header
    # no bare-key consult anywhere in the chatter source
    assert "sPlayerbotAIConfig.llmCloudChatter" not in text


def test_post_failure_floor_keeps_the_floor_guard_set_and_normal_stamp():
    text = src()
    leg = text.split("if (cloudFloorArmed)")[1].split("else\n                    {")[0]
    # its own enqueue guard set: effective spacing on lastFloorAt,
    # template spacing, queue cap, ring
    assert "now - s.lastFloorAt >= (time_t)cloudFloorSpacingSec" in leg
    assert "pocketllm::TemplateSpacingAdmits(s.fatigue, tpl," in leg
    assert "s.queue.size() < kQueueCap" in leg
    assert "pocketllm::RingAdmits(s.ring, entry.text)" in leg
    assert "entry.floor = true;" in leg
    # the entry rides the NORMAL delivery stamp: it is not longForm, so
    # the drain loop's budget peek + confirmed-delivery charge cover it
    # (no arbiter exemption)
    assert "entry.longForm = true;" not in leg
    # the doctrine survives inside the leg: the event ground is still
    # required (PickGossipRow above) and the line-safety law applies
    assert "pocketllm::ChatterLineSafe(floorText)" in leg
    assert "silence default: no event, no line, no floor" in text


def test_manual_override_floor_branch_is_byte_identical():
    text = src()
    assert MANUAL_OVERRIDE_FLOOR_SLICE in text, \
        "the dormant manual-override floor branch must stay byte-identical"
    # and it still uses the policy spacing (not the latch-doubled one) -
    # the two floor legs are independent
    assert "policy.floorMinSpacingSec) &&" in MANUAL_OVERRIDE_FLOOR_SLICE
    assert "cloudFloorSpacingSec" not in MANUAL_OVERRIDE_FLOOR_SLICE
