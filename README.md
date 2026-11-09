# EterHub

Le **village des lobbys** du réseau Akia (Paper 26.2, Java 25). Document développeur, à tenir à jour avec le code.
Le côté proxy (arrivée, `/lobby`, renvoi au lobby, lobbys jetables) est **EterVelocityLobby**.

- **EterLib 1.23.0+** (`depend`) : base, Redis, bus réseau, langues, menus, actions, téléportations.
- Facultatifs (`softdepend`) : **EterFriend** (amis : visibilité, amis en ligne, suivre un ami), **EterClan** (clan :
  visibilité, profil), EterNpc (PNJ des lieux, avec les actions `eterhub:*`).
- Ne démarre que sur un serveur dont le nom commence par `lobby-servers`. Ne touche **jamais** à l'inventaire ni au
  mode de jeu (EterSync peut tourner sur un lobby) : la vitesse au village passe par la vitesse de marche, remise à la
  normale au départ (avant EterSync).

## Principe : tout ce qui se pose en jeu est en base, commun à tous les lobbys

Limites des lieux, spawn et points d'arrivée, plaques, hologrammes, parcours, têtes, réglages changés en jeu : en
base (`eterhub_*`), relus par tous les lobbys au moindre changement (bus `eterhub`). Un lobby créé par
l'orchestrateur n'a rien à régler. Chaque changement d'éditeur est noté dans `eterhub_settings_log` (`/eterhub journal`).

## Modules

| Paquet | Contenu |
|---|---|
| `core` | `HubSettings` (réglages en jeu, journal), `HubStats` (statistiques par jour), `HubPlayers` (première venue, dernière position, visite, dernier serveur joué), `Rewards` (commandes console ou actions EterLib) |
| `zone` | `Zone` (config), `Region`/`ZoneIndex` (logique pure, testée : la plus petite zone l'emporte), `ZoneStore`, `ZoneService` (suivi aux changements de bloc, titre, son, heure et météo par joueur, accès réservé, découverte, ambiance), `VillageMap` (`/carte`), `ZoneCommand` |
| `spawn` | `Spawn` (spawn principal + points d'arrivée), `ArrivalListener` (lecture à l'arrivée dans `AsyncPlayerSpawnLocationEvent` priorité LOW : une téléportation réseau d'EterLib passe après et l'emporte), `Tour` (visite guidée), `VoidListener` |
| `protect` | `ProtectionListener` (tout le village figé et protégé), `BuildMode` (`/eterhub build`) |
| `movement` | `MovementListener` (double saut), `Pads` (lanceurs, ascenseurs, vitesse, téléporteurs, trampolines), `Cosmetics` (traînées et auras par permission) |
| `activity` | `Fishing` (pêche sans canne, trophées), `Archaeology` (fouilles, musée), `Parkour`, `Heads`, `HubEvents` (évènements, annonces), `LootTable`/`ParkourTime` (logique pure, testée) |
| `display` | `Holograms` (TextDisplay non gardés dans le monde : text, players, board, progress), `Tips` |
| `network` | `NetworkGui` (sélecteur, lobbys, maintenance, Reprendre), `ServerMenu`, `LobbyMenu`, `PortalListener` |
| `social` | `Visibility` (4 modes), `Seats`, `Profiles`, `FriendsView` (EterFriend / EterClan isolés dans `FriendLink` / `ClanLink`) |
| `admin` | `AdminCommand` (`/eterhub`), `HubActions` (actions EterLib), `HubApiService` |

## Commandes

| Joueurs | |
|---|---|
| `/spawn` · `/servers` · `/lobbies` · `/carte` · `/visite [passer\|recommencer]` | Navigation |
| `/visibility [all\|friends\|staff\|none]` | Qui l'on voit |
| `/peche` · `/musee` · `/parcours [<parcours>\|quitter\|retour]` | Activités |

| Staff (`eterhub.admin`) | |
|---|---|
| `/eterhub zone point\|undo\|clear\|ymin\|ymax\|height off\|pos1\|pos2\|set\|warp\|show\|tp\|remove\|list\|here` | Lieux : surface libre par points (hauteur facultative, pour un lieu sous un autre) ou pavé ; point d'arrivée de la carte |
| `/eterhub spawn set\|add\|clear\|list` (`setspawn` : ancien nom) | Spawn et points d'arrivée |
| `/eterhub pad add <launch\|elevator\|speed\|teleport> [force] [hauteur] [fixe]\|mark\|remove\|list` | Plaques (bloc où l'on est) |
| `/eterhub holo list\|create\|addline\|setline\|removeline\|move\|delete` | Hologrammes |
| `/eterhub parkour list\|start\|checkpoint\|undo\|finish\|delete <parcours>` | Parcours |
| `/eterhub head add\|remove\|count` | Chasse aux têtes (en visant la tête) |
| `/eterhub event start <minutes> <nom>\|stop` · `/eterhub annonce <texte>` | Évènements, annonces (tous les lobbys) |
| `/eterhub reglage [<chemin> [valeur]\|reset <chemin>]` | Réglages communs, en base |
| `/eterhub maintenance <serveur> on\|off` · `journal [n]` · `stats [jours] [préfixe]` · `reload` · `info` | Staff |
| `/eterhub build` (`eterhub.build`) | Mode construction |

## Actions EterLib (PNJ, menus)

`eterhub:servers`, `lobbies`, `spawn`, `server <nom>`, `zone <lieu>`, `map`, `visibility`, `fishing`, `museum`,
`tour [restart]`, `soon <quoi>`, `door <lieu>` (config `doors`) ; conditions `?eterhub:discovered <lieu>`,
`?eterhub:in-zone <lieu>`. Un lieu réservé peut demander une condition d'EterLib (`access.condition`, ex :
`?eterjob:rank mineur expert`), testée seulement si elle est fournie sur ce serveur.

## API

`HubApi` (spawn, menus, lieu d'un joueur, découvertes, aller à un lieu, carte, têtes, parcours, visite) et les
événements `HubZoneEnterEvent` (entrée dans un lieu, première visite) et `HubActivityEvent` (pêche, fouille, parcours,
tête, village découvert, visite finie) : de quoi brancher EterQuest / BetonQuest et des succès.
Classements EterLib fournis : `hub_fishing` (plus belle prise du mois, en grammes), `hub_parkour_<parcours>` (meilleur
temps, en millisecondes).

## Tables

`eterhub_settings`, `settings_log`, `stats`, `players`, `preferences`, `lobbies`, `spawn`, `zones`, `discoveries`,
`pads`, `holograms`, `catches`, `finds`, `parkour_points`, `parkour_times`, `heads`, `heads_found`.
Redis : `eterhub:event` (évènement en cours), `eterhub:maintenance` (serveurs en maintenance).

## Technique

- Tests : `ZoneIndexTest`, `LootTableTest`, `ParkourTimeTest` (`gradlew test`).
- Compilation : `gradlew build` → `build/libs/EterHub-<version>.jar`. Release : tag = version de `gradle.properties`.
