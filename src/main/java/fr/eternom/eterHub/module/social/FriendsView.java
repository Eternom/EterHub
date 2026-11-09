package fr.eternom.eterHub.module.social;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Les amis (EterFriend, facultatif) et le clan (EterClan, facultatif), vus d'EterHub. Sans ces plugins : personne.
 * Leurs API ne sont touchées que dans FriendLink / ClanLink, chargées seulement si le plugin tourne.
 */
public interface FriendsView {

    /** Amis d'un joueur connecté ici (sans base). */
    Set<UUID> friends(UUID player);

    /** Amis en ligne : ami -> serveur. Bloquant. */
    Map<UUID, String> online(UUID player);

    /** Membres de son clan (sans lui). Bloquant. */
    Set<UUID> clanMates(UUID player);

    /** Nom de son clan, vide sans clan. Bloquant. */
    String clanName(UUID player);

    boolean friendsAvailable();

    void openFriends(Player player);

    static FriendsView create() {
        boolean friends = Bukkit.getPluginManager().isPluginEnabled("EterFriend");
        boolean clans = Bukkit.getPluginManager().isPluginEnabled("EterClan");
        FriendsView friendPart = friends ? FriendLink.create() : null;
        ClanLink clanPart = clans ? ClanLink.create() : null;
        return new FriendsView() {
            @Override
            public Set<UUID> friends(UUID player) {
                return friendPart == null ? Set.of() : friendPart.friends(player);
            }

            @Override
            public Map<UUID, String> online(UUID player) {
                return friendPart == null ? Map.of() : friendPart.online(player);
            }

            @Override
            public Set<UUID> clanMates(UUID player) {
                return clanPart == null ? Set.of() : clanPart.mates(player);
            }

            @Override
            public String clanName(UUID player) {
                return clanPart == null ? "" : clanPart.name(player);
            }

            @Override
            public boolean friendsAvailable() {
                return friendPart != null;
            }

            @Override
            public void openFriends(Player player) {
                if (friendPart != null) {
                    friendPart.openFriends(player);
                }
            }
        };
    }
}
