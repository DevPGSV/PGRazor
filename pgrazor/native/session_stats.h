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
