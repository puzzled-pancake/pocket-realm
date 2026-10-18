"""W6-N3 privacy greps over REAL captured payloads.

The ask's gate: "privacy greps proving no IP/addr strings in any payload".
The payloads are the gateway E2E's captured wire bytes (the two-process
loopback join run writes them via -Dpocket.payload.dump into
gateway/src/test/resources/captured-payloads/ — committed fixtures from
the run, re-dumpable any time by re-running the E2E with the property):

- realm-list-request.bin: what the fake client sent (control).
- realm-list-rewritten.bin: what the JOIN GATEWAY DELIVERED to the client
  — the payload a real client would act on. This grep is the payload-level
  proof of the stage's invariant: the host's advertised address NEVER
  reaches the client; the only dialable address in the delivered payload
  is the joiner's own loopback world listener; no iroh/endpoint-discovery
  vocabulary (addr=) appears anywhere.

Grepped patterns:
- any IPv4 literal except 127.0.0.1 (the rewriter's own target),
- any inet_aton shorthand (digits-and-dots token with 2+ parts),
- any dotless decimal integer spelling of an address (8-10 digits as a
  standalone token is 2130706433-shaped; port-sized numbers are fine),
- the addr= substring (iroh's direct-address vocabulary).
"""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PAYLOAD_DIR = ROOT / "gateway" / "src" / "test" / "resources" / "captured-payloads"

ALLOWED_HOSTS = {"127.0.0.1"}

# Any dotted-quad (or dotted-shorthand) numeric token: the loose shape the
# listing fence also uses — every match must be the loopback target.
DOTTED_NUMERIC = re.compile(rb"(?<![\d.])(\d{1,3}\.){1,3}\d{1,3}(?![\d.])")
# The dotless decimal address spelling (inet_aton integer form).
DOTLESS_DECIMAL = re.compile(rb"(?<![\d.])(\d{8,10})(?![\d.])")


def test_captured_payloads_exist():
    """The evidence run's payloads must be present (a missing capture is a
    loud failure, not a silent pass)."""
    files = sorted(p.name for p in PAYLOAD_DIR.glob("*.bin"))
    assert "realm-list-request.bin" in files, f"missing capture; found {files}"
    assert "realm-list-rewritten.bin" in files, f"missing capture; found {files}"


def test_delivered_realm_list_carries_only_the_loopback_target():
    """THE payload a real client acts on: only the joiner's own loopback
    world listener may appear as an address; the host's advertised address
    and every other IPv4 literal are absent."""
    payload = (PAYLOAD_DIR / "realm-list-rewritten.bin").read_bytes()
    # The address C-string the client would dial:
    assert b"127.0.0.1:8085" in payload, (
        "the delivered realm list must carry the joiner's own loopback "
        f"world listener; payload: {payload.hex()}"
    )
    # The protocol's port word is 8085 little-endian inside the C-string;
    # no OTHER dotted numeric may appear.
    for match in DOTTED_NUMERIC.finditer(payload):
        host = match.group(0).decode("ascii")
        assert host in ALLOWED_HOSTS, (
            f"a non-loopback address literal ({host}) reached the client "
            f"payload: {payload.hex()}"
        )
    # The dotless decimal spellings (2130706433 = 127.0.0.1) are refused
    # too — but the frame's legit small numbers (counts, 0.5f bytes) are
    # not address-shaped; only 8-10 digit tokens count.
    for match in DOTLESS_DECIMAL.finditer(payload):
        raise AssertionError(
            f"a dotless decimal address spelling ({match.group(0).decode()}) "
            f"reached the client payload: {payload.hex()}"
        )
    # iroh's direct-address vocabulary never appears.
    assert b"addr=" not in payload, "the delivered payload carries addr="


def test_request_payload_stays_opaque_to_the_client_lane():
    """The client's own request frame carries no addresses at all (the
    CMSG_REALM_LIST is a bare request — nothing to leak outbound)."""
    request = (PAYLOAD_DIR / "realm-list-request.bin").read_bytes()
    assert DOTTED_NUMERIC.search(request) is None, (
        f"the client request carries an address literal: {request.hex()}"
    )
    assert b"addr=" not in request
