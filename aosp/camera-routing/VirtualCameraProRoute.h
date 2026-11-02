// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <charconv>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>

namespace android::vcam {
struct Route {
    std::string physical;
    std::string synthetic;
    int64_t expiresMs;
};
inline bool safeId(std::string_view id) {
    if (id.empty() || id.size() > 32) return false;
    for (char c : id) {
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') ||
              (c >= 'A' && c <= 'Z') || c == '_')) return false;
    }
    return true;
}
// One atomic property record; CLOCK_BOOTTIME milliseconds, matching SystemClock.
inline std::optional<Route> parseRoute(std::string_view record, int64_t nowMs) {
    const auto first = record.find(',');
    if (first == record.npos) return std::nullopt;
    const auto second = record.find(',', first + 1);
    if (second == record.npos || record.find(',', second + 1) != record.npos) return std::nullopt;
    const auto physical = record.substr(0, first);
    const auto synthetic = record.substr(first + 1, second - first - 1);
    const auto expiry = record.substr(second + 1);
    int64_t expiresMs = 0;
    const auto result = std::from_chars(expiry.data(), expiry.data() + expiry.size(), expiresMs);
    if (result.ec != std::errc{} || result.ptr != expiry.data() + expiry.size() ||
        expiresMs <= nowMs || expiresMs - nowMs > 5000 ||
        !safeId(physical) || !safeId(synthetic) || physical == synthetic ||
        synthetic.substr(0, 3) != "v0_") return std::nullopt;
    return Route{std::string(physical), std::string(synthetic), expiresMs};
}
inline std::string resolve(const std::string& id, std::string_view record, int64_t nowMs) {
    auto route = parseRoute(record, nowMs);
    return route && route->physical == id ? route->synthetic : id;
}
}  // namespace android::vcam
