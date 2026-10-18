import com.pocketrealm.discovery.iroh.IrohJoinDescriptor;
var C = IrohJoinDescriptor.Companion;
StringBuilder host = new StringBuilder();
for (int i = 0; i < 16200; i++) host.appendCodePoint(0x20000); // CJK ideograph ext-B, isLetter()==true
String relay = "https://" + host + ".com";
System.out.println("P6e relay Kotlin chars: " + relay.length() + ", UTF-16 units: " + (relay.codePointCount(0, relay.length()) + (relay.length() - relay.codePointCount(0, relay.length()))));
try {
    IrohJoinDescriptor d = C.fromListingJson(new org.json.JSONObject("{\"type\":\"iroh\",\"node\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\",\"relay\":\"" + relay + "\"}"));
    System.out.println("P6e astral-letter hostname relay ACCEPTED (relay len " + d.getRelayUrl().length() + ")");
    long utf16 = d.getRelayUrl().chars().mapToLong(c -> c > 0xFFFF ? 2 : 1).sum();
    int fixed = 382; // java+tmpdir+jar+fixed args, measured in the P7 probe
    System.out.println("P6e command-line UTF-16 units ~ " + (utf16 + fixed) + " (limit 32767) -> exceeds: " + (utf16 + fixed > 32767));
} catch (Exception e) { System.out.println("P6e REFUSED: " + String.valueOf(e.getMessage()).substring(0, Math.min(100, e.getMessage().length()))); }
/exit
