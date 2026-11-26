#pragma once
#include <cstdint>

uint64_t pgr_research_progression_lease(uint64_t now_ms) noexcept;

void pgr_research_bag_observed(void *bag, void *player, const uint16_t *id, uint16_t length,
                              uint64_t observed, uint64_t lease, uint64_t started) noexcept;
