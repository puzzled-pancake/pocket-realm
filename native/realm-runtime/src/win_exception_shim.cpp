/*
 * win_exception_shim.cpp — Windows/MSVC lane only.
 *
 * Boost's exception-disabled declaration path (selected when a TU is
 * compiled without unwind semantics, e.g. through a precompiled header
 * created before /EHsc applies) leaves out-of-line declarations of
 * boost::throw_exception referenced by asio detail objects. On the
 * Android/NDG lane every TU compiles with exceptions enabled and the
 * inline definitions win; on MSVC the linker can still see the out-of-line
 * references from such objects.
 *
 * This TU is compiled with BOOST_NO_EXCEPTIONS so the header only DECLARES
 * the overloads, and the definitions below satisfy them. They are
 * unreachable in a correctly configured build (/EHsc is forced onto both
 * runtime targets in CMakeLists.txt); if one is ever reached it aborts
 * loudly rather than propagating a half-thrown exception.
 */

#define BOOST_NO_EXCEPTIONS 1

#include <boost/throw_exception.hpp>
#include <cstdio>
#include <cstdlib>

namespace boost {

void throw_exception(std::exception const& e)
{
    std::fprintf(stderr, "boost::throw_exception reached (no-exceptions path): %s\n",
                 e.what());
    std::fflush(stderr);
    std::abort();
}

#if defined(BOOST_NO_EXCEPTIONS)
void throw_exception(std::exception const& e, boost::source_location const&)
{
    std::fprintf(stderr, "boost::throw_exception reached (no-exceptions path): %s\n",
                 e.what());
    std::fflush(stderr);
    std::abort();
}
#endif

} // namespace boost
