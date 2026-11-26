#pragma once
#include <stdint.h>
struct PgrSharedMetadataOutput;
// Once a minute on the Unity owner, reads the signed-in player's lifetime counters (PlayerStatsProto)
// and reports them as a "session-stats" event. Read-only; no game request is sent.
void pgr_session_stats_tick(uint64_t tid, const PgrSharedMetadataOutput *metadata);

bool pgr_local_api_trainer_requested() noexcept;
// Receives the exact authenticated player already resolved by the account observer.
void pgr_local_api_trainer(void *player, const uint16_t *id, uint16_t id_len,
                           const uint16_t *name, uint16_t name_len, uint64_t observed_ms) noexcept;

bool pgr_collection_bag_classes(void **contract, void **implementation) noexcept;
void pgr_collection_request(bool research, uint64_t request, uint64_t lease, uint64_t deadline) noexcept;
bool pgr_collection_pending(uint64_t now, uint64_t research_lease) noexcept;
void pgr_collection_cleanup(uint64_t research_lease) noexcept;
void pgr_collection_slice(void *bag, void *player, const uint16_t *id, uint16_t length,
                          uint64_t research_lease, uint64_t started) noexcept;
