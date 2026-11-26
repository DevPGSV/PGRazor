#pragma once
#include <cstdint>

uint64_t pgr_research_progression_lease(uint64_t now_ms) noexcept;

bool pgr_research_bag_classes(void **contract, void **implementation) noexcept;
void pgr_research_bag_observed(void *bag, void *player, const uint16_t *id, uint16_t length,
                              uint64_t observed, uint64_t lease, uint64_t started) noexcept;

void pgr_research_export_request(uint64_t request, uint64_t lease, uint64_t deadline) noexcept;
bool pgr_research_export_pending(uint64_t now, uint64_t lease) noexcept;
void pgr_research_export_cleanup() noexcept;
void pgr_research_export_slice(void *bag, void *player, const uint16_t *id, uint16_t length,
                               uint64_t lease, uint64_t started) noexcept;
