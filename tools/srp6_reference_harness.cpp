/*
 * srp6_reference_harness — prints SERVER-SIDE and CLIENT-SIDE SRP6
 * values for fixed inputs, straight from the cmangos sources
 * (SRP6.cpp/BigNumber.cpp), so client implementations can be diffed
 * stage by stage against the exact engine the realm runs.
 *
 * Bring-up/diagnostic tool only (not shipped).
 */
#include "Auth/SRP6.h"

#include <algorithm>
#include <cstdio>
#include <cstring>

int main()
{
    setvbuf(stdout, nullptr, _IONBF, 0);
    char const* salt_hex = "21ff98a91124cb0bb1ec567a7f96e55000000000000000000000000000000ff";
    char const* identity_hex = "0123456789abcdef0123456789abcdef01234567";
    char const* a_hex = "deadbeef00112233445566778899aabbccddeeff01";
    char const* login = "PROBE";

    SRP6 seed;
    if (!seed.CalculateVerifier(identity_hex, salt_hex))
    {
        fprintf(stderr, "verifier failed\n");
        return 1;
    }
    char const* v_hex = seed.GetVerifier().AsHexStr();
    printf("v=%s\n", v_hex);

    SRP6 srv;
    srv.SetVerifier(v_hex);
    srv.SetSalt(salt_hex);
    srv.CalculateHostPublicEphemeral();
    printf("B=%s\n", srv.GetHostPublicEphemeral().AsHexStr());

    BigNumber a;
    a.SetHexStr(a_hex);
    BigNumber A = srv.GetGeneratorModulo().ModExp(a, srv.GetPrime());
    printf("A=%s\n", A.AsHexStr());

    /* the server receives A as 32 wire bytes; SetBinary reverses them,
     * so a client wanting the server to see THIS A sends its
     * little-endian form (the AsByteArray default). */
    std::vector<uint8> a_wire = A.AsByteArray(32);
    if (!srv.CalculateSessionKey(a_wire.data(), (int)a_wire.size()))
    {
        fprintf(stderr, "session key failed\n");
        return 1;
    }
    srv.HashSessionKey();
    srv.CalculateProof(login);
    printf("M=%s\n", srv.GetProof().AsHexStr());
    printf("K=%s\n", srv.GetStrongSessionKey().AsHexStr());

    /* Client-side reconstruction with public pieces: u exactly the way
     * CalculateSessionKey computes it, then S = (B - 3v)^(a + u*x).
     * x is recomputed through the verifier's own digest path. */
    BigNumber s;
    s.SetHexStr(salt_hex);
    BigNumber i;
    i.SetHexStr(identity_hex);
    uint8 ident[20];
    memset(ident, 0, sizeof(ident));
    std::vector<uint8> vi = i.AsByteArray();
    memcpy(ident, vi.data(), vi.size());
    std::reverse(ident, ident + 20);
    Sha1Hash xsha;
    xsha.UpdateData(s.AsByteArray());
    xsha.UpdateData(ident, 20);
    BigNumber x;
    x.SetBinary(xsha.GetDigest(), xsha.GetLength());

    Sha1Hash usha;
    usha.UpdateBigNumbers(&A, &srv.GetHostPublicEphemeral(), nullptr);
    usha.Finalize();
    BigNumber u;
    u.SetBinary(usha.GetDigest(), usha.GetLength());
    printf("clientU=%s\n", u.AsHexStr());
    printf("clientX=%s\n", x.AsHexStr());

    BigNumber exp = a + u * x;
    BigNumber base = srv.GetHostPublicEphemeral() - seed.GetVerifier() * 3;
    BigNumber clientS = base.ModExp(exp, srv.GetPrime());
    printf("clientS=%s\n", clientS.AsHexStr());
    return 0;
}
