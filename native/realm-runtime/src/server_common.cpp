#include "server_common.h"

#include <algorithm>
#include <chrono>
#include <cstring>

namespace pocket_server {

uint64_t monotonic_ms()
{
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
}

void copy_detail(char* destination, size_t capacity, const std::string& value)
{
    if (!destination || capacity == 0) return;
    const size_t length = std::min(capacity - 1, value.size());
    std::memcpy(destination, value.data(), length);
    destination[length] = '\0';
}

void StateRecord::transition(pocket_server_state state, pocket_server_error error,
                             const std::string& detail)
{
    {
        std::lock_guard<std::mutex> guard(m_mutex);
        m_detail = detail.substr(0, POCKET_SERVER_ERROR_MAX - 1);
    }
    m_error.store(error, std::memory_order_release);
    m_state.store(state, std::memory_order_release);
    beat();
}

std::string StateRecord::detail() const
{
    std::lock_guard<std::mutex> guard(m_mutex);
    return m_detail;
}

} // namespace pocket_server

extern "C" const char* pocket_server_error_string(pocket_server_error error)
{
    switch (error)
    {
        case POCKET_SERVER_OK: return "OK";
        case POCKET_SERVER_INVALID_ARGUMENT: return "INVALID_ARGUMENT";
        case POCKET_SERVER_WRONG_STATE: return "WRONG_STATE";
        case POCKET_SERVER_CONFIG: return "CONFIG";
        case POCKET_SERVER_DB_CONNECT: return "DB_CONNECT";
        case POCKET_SERVER_DB_REVISION: return "DB_REVISION";
        case POCKET_SERVER_DATA_MISSING: return "DATA_MISSING";
        case POCKET_SERVER_DATA_BUILD: return "DATA_BUILD";
        case POCKET_SERVER_PORT_IN_USE: return "PORT_IN_USE";
        case POCKET_SERVER_TIMEOUT: return "TIMEOUT";
        case POCKET_SERVER_ACCOUNT_EXISTS: return "ACCOUNT_EXISTS";
        case POCKET_SERVER_ACCOUNT_REJECTED: return "ACCOUNT_REJECTED";
        case POCKET_SERVER_INTERNAL: return "INTERNAL";
    }
    return "UNKNOWN";
}
