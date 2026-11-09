package fr.eternom.eterHub.module.social;

import fr.eternom.eterFriend.api.FriendApi;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** EterFriend vu d'EterHub : chargé seulement si EterFriend tourne ici. */
final class FriendLink implements FriendsView {

    private FriendLink() {
    }

    static FriendsView create() {
        return FriendApi.get().isPresent() ? new FriendLink() : null;
    }

    private static FriendApi api() {
        return FriendApi.get().orElseThrow();
    }

    @Override
    public Set<UUID> friends(UUID player) {
        return FriendApi.get().map(api -> api.cachedFriends(player)).orElse(Set.of());
    }

    @Override
    public Map<UUID, String> online(UUID player) {
        return FriendApi.get().map(api -> api.onlineFriends(player)).orElse(Map.of());
    }

    @Override
    public Set<UUID> clanMates(UUID player) {
        return Set.of();
    }

    @Override
    public String clanName(UUID player) {
        return "";
    }

    @Override
    public boolean friendsAvailable() {
        return true;
    }

    @Override
    public void openFriends(Player player) {
        api().openMenu(player);
    }
}
