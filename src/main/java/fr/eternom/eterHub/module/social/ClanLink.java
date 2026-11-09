package fr.eternom.eterHub.module.social;

import fr.eternom.eterClan.api.ClanApi;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** EterClan vu d'EterHub : chargé seulement si EterClan tourne ici. Bloquant. */
final class ClanLink {

    private ClanLink() {
    }

    static ClanLink create() {
        return ClanApi.get().isPresent() ? new ClanLink() : null;
    }

    Set<UUID> mates(UUID player) {
        return ClanApi.get().flatMap(api -> api.clanOf(player)).map(clan -> {
            Set<UUID> members = new HashSet<>(clan.members());
            members.add(clan.owner());
            members.remove(player);
            return members;
        }).orElse(Set.of());
    }

    String name(UUID player) {
        return ClanApi.get().flatMap(api -> api.clanOf(player)).map(ClanApi.ClanInfo::name).orElse("");
    }
}
