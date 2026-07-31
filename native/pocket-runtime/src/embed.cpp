// embed.cpp — the throw side of the POCKET_FATAL embedding patch.
//
// Errors.h forward-declares pocket_realm::embed::throw_fatal(msg); this file
// defines it. Keeping the throw here (not in Errors.h) means upstream translation
// units only need the forward declaration — no exception headers leak into the
// CMaNGOS tree, and the fatal_error type stays internal to the runtime.
//
// When POCKET_EMBEDDED is NOT defined, POCKET_FATAL expands to ::exit(1) and this
// file is simply not linked (it is only in the libpocketrealm.so target).

#include "embed.h"

#include <exception>
#include <string>

namespace pocket_realm {

class fatal_error final : public std::exception
{
public:
    explicit fatal_error(const char* msg) : m_msg(msg ? msg : "pocket realm fatal error") {}
    const char* what() const noexcept override { return m_msg.c_str(); }
private:
    std::string m_msg;
};

namespace embed {

[[noreturn]] void throw_fatal(const char* msg)
{
    throw fatal_error(msg);
}

} // namespace embed

// Exposed for the facade, which needs to recognize the type at the catch site
// and report a structured error. Defined here so the type layout is owned by
// exactly one translation unit.
bool is_fatal_error(const std::exception_ptr& ep, std::string* out_msg) noexcept
{
    try
    {
        if (ep) std::rethrow_exception(ep);
    }
    catch (const fatal_error& e)
    {
        if (out_msg) *out_msg = e.what();
        return true;
    }
    catch (...)
    {
        return false;
    }
    return false;
}

} // namespace pocket_realm
