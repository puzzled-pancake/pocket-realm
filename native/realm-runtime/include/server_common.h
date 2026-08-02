#pragma once

#include "pocket_server.h"

#include <atomic>
#include <mutex>
#include <string>

namespace pocket_server {

uint64_t monotonic_ms();
void copy_detail(char* destination, size_t capacity, const std::string& value);

class StateRecord {
public:
    void transition(pocket_server_state state, pocket_server_error error = POCKET_SERVER_OK,
                    const std::string& detail = {});
    pocket_server_state state() const { return m_state.load(std::memory_order_acquire); }
    pocket_server_error error() const { return m_error.load(std::memory_order_acquire); }
    std::string detail() const;
    uint64_t heartbeat() const { return m_heartbeat.load(std::memory_order_acquire); }
    void beat() { m_heartbeat.store(monotonic_ms(), std::memory_order_release); }

private:
    std::atomic<pocket_server_state> m_state{POCKET_SERVER_STOPPED};
    std::atomic<pocket_server_error> m_error{POCKET_SERVER_OK};
    std::atomic<uint64_t> m_heartbeat{0};
    mutable std::mutex m_mutex;
    std::string m_detail;
};

} // namespace pocket_server
