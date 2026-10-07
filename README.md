# EterHub

Le contenu des **lobbys** (Paper, à installer seulement sur eux). Document développeur, à tenir à jour avec le code.
Le côté proxy (arrivée sur le lobby le moins rempli, `/lobby`, renvoi au lobby, annonces d'arrivée) est
**EterVelocityLobby** ; les lobbys sont la liste `try` de `velocity.toml`.

## Prérequis

- **EterLib 1.7.0+** (`depend`) : base, langues et textes communs, menus (cadre, bouton Retour), état des serveurs
  (`getServers().isOnline`), joueurs par serveur (`countByServer`), envoi vers un serveur (`getTeleports().connect`).
- **Ne touche jamais à l'inventaire ni au mode de jeu** : EterSync peut tourner sur un lobby (pas d'objets dans la
  barre ; les menus s'ouvrent par commande, à relier à des PNJ, panneaux ou d'autres menus via `back-command`).

## Modules

- **`spawn`** : le spawn de ce lobby (`config.yml > spawn`, réglé par `/eterhub setspawn`), arrivée au spawn
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
  lobbys où EterHub est installé, table `eterhub_lobbies`, chacun s'y inscrit au démarrage). Joueurs et lobbys relus
  toutes les 5 s en tâche de fond. Clic : « tu es ici », « hors ligne », ou envoi sur le serveur.
- **Portails** (`network/PortalListener`, `portals.open-selector`) : un portail du Nether au lobby ne mène nulle part ;
  y entrer ouvre `/servers` (une fois par passage). Les blocs de portail tiennent sans cadre d'obsidienne (physique
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
