# EterHub

Le contenu des **lobbys** (Paper, à installer seulement sur eux). Document développeur, à tenir à jour avec le code.
Le côté proxy (arrivée sur le lobby le moins rempli, `/lobby`, renvoi au lobby, annonces d'arrivée) est
**EterVelocityLobby** ; les lobbys sont la liste `try` de `velocity.toml`.

## Prérequis

- **EterLib 1.10.0+** (`depend`) : base, langues et textes communs, menus (cadre, bouton Retour), état des serveurs
  (`getServers().isOnline`), joueurs par serveur (`countByServer`), envoi vers un serveur (`getTeleports().connect`).
- **Ne touche jamais à l'inventaire ni au mode de jeu** : EterSync peut tourner sur un lobby (pas d'objets dans la
  barre ; les menus s'ouvrent par commande, à relier à des PNJ, panneaux ou d'autres menus via `back-command`).
- **Ne démarre que sur un lobby** : nom du serveur (`server-name` d'EterLib) commençant par un préfixe de
  `lobby-servers` (`lobby` par défaut, comme les lobbys de l'orchestrateur). Sinon il s'arrête sans rien toucher : ses
  règles de monde (`spawn_mobs`, `advance_time`, `advance_weather`) restent dans le monde même après l'avoir retiré, et
  son double saut laisse le droit de voler aux joueurs (corrigé à la connexion par EterEssential 1.0.8).

## Modules

- **`spawn`** : le spawn COMMUN à tous les lobbys, en base (`eterhub_spawn`, réglé par `/eterhub setspawn`, appliqué tout
  de suite aux autres lobbys par le bus réseau d'EterLib, canal `eterhub`) : un lobby neuf n'a rien à régler. Arrivée au spawn
  (`spawn-on-join`), `/spawn`. `/lobby` et `/hub` sont pris par le proxy, d'où `/eterhub` pour l'administration.
- **`protect`** : pas de dégâts ni de faim ; casser, poser, seaux, jeter, ramasser, coffres, cadres, supports d'armure
  et terre labourée réservés à `eterhub.build` ; les joueurs ne frappent aucune entité. Les PNJ (Mannequins
  d'EterMarket) restent cliquables. Le vide (`world.void-y`) ramène au spawn. Au démarrage, règles de jeu de tous les
  mondes : heure figée (`world.time`, `ADVANCE_TIME`), beau temps (`ADVANCE_WEATHER`), pas d'apparitions naturelles
  (`SPAWN_MOBS`).
- **`movement`** : double saut (vol « autorisé » pour capter la double pression sur espace, remplacé par une
  propulsion, rendu en touchant le sol ; jamais pour `eterhub.fly`, le staff vole normalement) et plaques de lancement
  (`launch-pads.plate`, une fois par seconde).
- **`network`** : `/servers` (sélecteur : `selector.servers` = nom dans `velocity.toml` → icône et case ; nom affiché
  d'EterLib, description `selector.description.<serveur>` dans lang/, joueurs, en ligne ou non) et `/lobbies` (les
  lobbys où EterHub TOURNE, table `eterhub_lobbies` : chacun y écrit son signe de vie toutes les 5 s, seuls ceux vus
  depuis moins de 30 s sont listés, les lignes muettes depuis 10 min sont effacées ; seuls ceux en ligne
  sont affichés : un ancien nom ou un lobby arrêté ne s'y voit pas). Joueurs et lobbys relus
  toutes les 5 s en tâche de fond. Clic : « tu es ici », « hors ligne », ou envoi sur le serveur.
- **Portails** (`network/PortalListener`, `portals.open-selector`) : un portail du Nether au lobby ne mène nulle part ;
  y entrer replace le joueur juste devant (le jeu ferme tout menu tant qu'on est DANS un portail) puis ouvre
  `/servers`. Les blocs de portail tiennent sans cadre d'obsidienne (physique
  annulée pour eux) : on les pose avec WorldEdit (`//set nether_portal`) dans n'importe quel décor.
- **`visibility`** : `/visibility` masque ou réaffiche les autres joueurs pour soi ; choix en base
  (`eterhub_preferences`), le même sur tous les lobbys ; les nouveaux venus restent masqués pour qui les masque.

## Permissions

| Permission | Par défaut | Rôle |
|---|---|---|
| `eterhub.spawn`, `eterhub.servers`, `eterhub.lobbies`, `eterhub.visibility` | tous | Commandes des joueurs |
| `eterhub.build` | op | Construire et interagir librement au lobby |
| `eterhub.fly` | op | Pas de double saut (vol normal) |
| `eterhub.admin` | op | Tout, dont `/eterhub setspawn` |

## API (pour les autres plugins)

`fr.eternom.eterHub.api.HubApi` (`HubApi.get()`, présente seulement sur un lobby) : personne d'autre ne lit les tables
`eterhub_*`.

- `spawn()`, `teleportToSpawn(joueur)` ; `openServers(joueur)`, `openLobbies(joueur)` (pour les relier à un PNJ...).

`eterhub_lobbies` : chaque lobby y écrit son signe de vie et EterHub efface lui-même les lignes trop vieilles
(l'orchestrateur n'y touche plus).
