// embed.h — embedding boundary helpers shared across pocket-runtime sources.
#pragma once

#include <exception>
#include <string>

namespace pocket_realm {

namespace embed {
// Forward-declared in upstream Errors.h (POCKET_EMBEDDED). Defined in embed.cpp.
// Translates a startup exit() into a catchable exception at the ABI boundary.
[[noreturn]] void throw_fatal(const char* msg);
} // namespace embed

// Returns true and fills *out_msg if `ep` holds a fatal_error thrown by
// POCKET_FATAL. The facade uses this to distinguish client-data/startup gates
// (REALM_E_BLOCKED_ON_CLIENT_DATA / REALM_E_FATAL_STARTUP) from other throws.
// noexcept: catches everything; never throws.
bool is_fatal_error(const std::exception_ptr& ep, std::string* out_msg) noexcept;

} // namespace pocket_realm
