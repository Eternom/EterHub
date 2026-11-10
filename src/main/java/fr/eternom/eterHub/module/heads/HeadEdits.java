package fr.eternom.eterHub.module.heads;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Les modifications d'une tête de la chasse, communes à la commande (/eterhub head, en visant la tête) et au menu
 * d'édition (/eterhub head list, clic sur une tête) : nom, rare, chasse, retrait, skin. Base en tâche de fond, journal,
 * puis relecture ; after est lancé sur le thread principal une fois les têtes relues.
 */
public class HeadEdits {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern HASH = Pattern.compile("[0-9a-fA-F]{32,}");
    private static final String TEXTURES_URL = "https://textures.minecraft.net/texture/";

    private final HeadService service;
    private final HubSettings settings;
    private final Messages messages;

    public HeadEdits(HeadService service, HubSettings settings) {
        this.service = service;
        this.settings = settings;
        this.messages = service.messages();
    }

    public void rename(Player player, HeadStore.Head head, String name, Runnable after) {
        String stored = name == null || name.isBlank() || name.equals("-") ? "" : name.length() > 64 ? name.substring(0, 64) : name;
        String author = player.getName();
        Tasks.async(service.plugin(), player, () -> {
            service.store().setName(head.id(), stored);
            settings.log("head-name:" + head.id(), head.name(), stored, author);
            return true;
        }, done -> {
            messages.send(player, "editor.head.named", "name", stored.isEmpty() ? "-" : stored);
            service.changed(after);
        }, () -> messages.send(player, "error.generic"));
    }

    public void toggleRare(Player player, HeadStore.Head head, Runnable after) {
        String author = player.getName();
        Tasks.async(service.plugin(), player, () -> {
            service.store().setRare(head.id(), !head.rare());
            settings.log("head-rare:" + head.id(), String.valueOf(head.rare()), String.valueOf(!head.rare()), author);
            return !head.rare();
        }, rare -> {
            messages.send(player, rare ? "editor.head.rare-on" : "editor.head.rare-off");
            service.changed(after);
        }, () -> messages.send(player, "error.generic"));
    }

    public void move(Player player, HeadStore.Head head, String hunt, Runnable after) {
        if (!service.hunts().containsKey(hunt)) {
            messages.send(player, "editor.head.unknown-hunt", "hunts", String.join(", ", service.hunts().keySet()));
            return;
        }
        String author = player.getName();
        Tasks.async(service.plugin(), player, () -> {
            service.store().setHunt(head.id(), hunt);
            settings.log("head-hunt:" + head.id(), head.hunt(), hunt, author);
            return true;
        }, done -> {
            messages.send(player, "editor.head.moved", "hunt", service.huntName(player, hunt));
            service.changed(after);
        }, () -> messages.send(player, "error.generic"));
    }

    public void remove(Player player, HeadStore.Head head, Runnable after) {
        String author = player.getName();
        Tasks.async(service.plugin(), player, () -> {
            boolean removed = service.store().remove(head.id());
            if (removed) {
                settings.log("head", head.hunt() + " " + head.x() + "," + head.y() + "," + head.z(), null, author);
            }
            return removed;
        }, removed -> {
            messages.send(player, removed ? "editor.head.removed" : "editor.head.unknown");
            service.changed(after);
        }, () -> messages.send(player, "error.generic"));
    }

    /**
     * Le skin de la tête, d'après source : une clé de heads.textures, un pseudo (son skin, demandé à Mojang), une URL
     * textures.minecraft.net, le hash seul de cette URL, ou la valeur « textures » en base64 (minecraft-heads.com,
     * champ « Value »). Le bloc posé suit (ici tout de suite, sur les autres lobbys à la relecture).
     */
    public void skin(Player player, HeadStore.Head head, String source, Runnable after) {
        String input = source == null ? "" : source.trim();
        if (input.isEmpty()) {
            messages.send(player, "editor.head.skin-usage");
            return;
        }
        ConfigurationSection textures = service.plugin().getConfig().getConfigurationSection("heads.textures");
        String configured = textures != null ? textures.getString(input) : null;
        String author = player.getName();
        Tasks.async(service.plugin(), player, () -> {
            String[] skin = configured != null ? new String[]{configured, null} : resolve(input);
            if (skin == null) {
                return false;
            }
            service.store().setSkin(head.id(), skin[0], skin[1]);
            settings.log("head-skin:" + head.id(), null, input.length() > 40 ? input.substring(0, 40) + "…" : input, author);
            return true;
        }, done -> {
            if (!done) {
                messages.send(player, "editor.head.skin-unknown", "source", input.length() > 40 ? input.substring(0, 40) + "…" : input);
                return;
            }
            messages.send(player, "editor.head.skin-done");
            service.changed(after);
        }, () -> messages.send(player, "error.generic"));
    }

    /** {texture, signature} ou null. Bloquant (Mojang pour un pseudo) : en tâche de fond. */
    private static String[] resolve(String input) {
        if (NAME.matcher(input).matches()) {
            PlayerProfile profile = Bukkit.createProfile(input);
            if (!profile.complete(true)) {
                return null;
            }
            for (ProfileProperty property : profile.getProperties()) {
                if ("textures".equals(property.getName())) {
                    return new String[]{property.getValue(), property.getSignature()};
                }
            }
            return null;
        }
        if (input.startsWith("http://") || input.startsWith("https://")) {
            return input.contains("textures.minecraft.net/texture/") ? new String[]{fromUrl(input.replace("http://", "https://")), null} : null;
        }
        if (HASH.matcher(input).matches()) {
            return new String[]{fromUrl(TEXTURES_URL + input.toLowerCase(java.util.Locale.ROOT)), null};
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(input), StandardCharsets.UTF_8);
            return decoded.contains("textures") && decoded.contains("SKIN") ? new String[]{input, null} : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String fromUrl(String url) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
