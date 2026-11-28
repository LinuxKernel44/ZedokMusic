# ZedokMusic — Chantier « parité FL Studio »

Document de travail : tout ce qui manque par rapport à FL Studio, découpé en phases, avec l'ordre, les dépendances, les critères de réussite et les risques.
Mis à jour à chaque version. Case cochée = livré **et** vérifié (sur appareil ou hors ligne, précisé).

Règles de travail (inchangées) :
- Releases alpha locales sur GitHub (`LinuxKernel44/ZedokMusic`), SHA256SUMS vérifié par re-téléchargement, README mis à jour, `versionCode`/`versionName` incrémentés.
- Interface en anglais américain. Aucune icône ni code copié : tout est dessiné / écrit à la main (licences).
- Test sur le S10 avec l'APK debug (`adb install -r --user 0`). Ce qui n'est pas vérifié à l'oreille ou sur appareil est dit explicitement.
- Pas de `/code-review ultra` (coût hors abonnement).
- Compatibilité : un projet enregistré par une version plus ancienne doit toujours s'ouvrir (voir phase 0).

---

## Vue d'ensemble

| Phase | Thème | Taille | Dépend de |
|---|---|---|---|
| 0 | Fondations et bugs bloquants | S | – |
| 1 | Piano roll : vélocité et outils de notes | M | 0 |
| 2 | Patterns et Playlist | XL | 0 |
| 3 | Marqueurs, carte de tempo et de signature | L | 2 |
| 4 | Routage du mixer (sends, bus, groupes, sidechain), PDC, freeze | XL | 0 |
| 5 | Effets natifs de mixage et mesure | XL | 4 |
| 6 | Clips audio : fondus, courbes, time-stretch HQ, Edison, correction de hauteur | XL | 0 |
| 7 | Automation et modulation | L | 2, 3 |
| 8 | Formats de sons : SF2, SFZ complet, Decent Sampler, CLAP | L | 0 |
| 9 | Interopérabilité : MIDI, stems, FLP | XL | 2, 3, 4 |
| 10 | Finition et dette | M | tout |

Taille : S ≈ une version, M ≈ 2–4, L ≈ 5–8, XL ≈ 8+ versions.

Ordre recommandé : 0 → 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10. Les phases 6 et 8 ne dépendent pas des patterns et peuvent s'intercaler si une phase lourde bloque.

---

## Phase 0 — Fondations et bugs bloquants

But : ne pas construire sur du sable. Tout ce qui suit change le modèle de données ou le moteur.

- [x] **0.1 Tempo réel passé aux plugins** (0.49.6, mesuré sur S10 avec un plugin VST2 sonde, voir `native/testfx/vst2test.cpp` ; chemin VST3 modifié mais non mesuré). Le README dit « fixé à 120 BPM » : tout plugin synchronisé au tempo (delay, LFO, arpégiateur VST) est faux. Passer le BPM du projet (et plus tard la carte de tempo) dans le `ProcessData` VST3/VST2 et aux processeurs natifs (`zdsp::Processor`). *Critère : un delay VST calé sur 1/4 tombe sur le temps à 90 et à 170 BPM (mesure hors ligne, rendu comparé à l'analyse des pics).*
- [~] **0.2 Signature et position musicale transmises aux plugins** (0.49.6 : position en quarts, début de mesure, lecture/arrêt, signature 4/4 fixe ; la signature variable attend la phase 3) (numérateur/dénominateur, position en quarts, début de mesure, état lecture/boucle). Prépare la phase 3.
- [x] **0.3 Versionnage du format de projet** (0.49.7 : `ProjectFormat.kt`, format 2, migrations ordonnées, fichier d'un format plus récent refusé et jamais écrasé, 7 tests + 4 projets témoins ; vérifié sur S10 : un projet v1 s'ouvre et se réenregistre en v2 avec sa sauvegarde, un projet « v99 » reste intact). Champ `format` dans le `.zedok`, migrations ordonnées, tests de chargement de projets d'anciennes versions (garder 3 fichiers témoins dans `app/src/test/resources`). Toute phase qui change le modèle ajoute une migration.
- [~] **0.4 Historique d'annulation unifié** (0.49.8 : `History.kt` + 8 tests ; notes, clips audio, mixer, tempo, gamme, renommage, automation ; vérifié sur S10 : mute, ajout de note, import / suppression de clip avec undo et redo en chaîne. Reste : ajout/suppression de pistes et d'effets, paramètres de plugin, presets.) (undo/redo global, pas seulement piano roll), avec une pile d'actions nommées. Nécessaire avant patterns/routage, qui multiplient les actions.
- [x] **0.5 Moteur : graphe de rendu explicite** (0.49.9 : pistes → bus → master avec ordre planifié et refus des boucles ; rendu des 7 scènes d'avant bit-exact, 2 scènes de bus ajoutées + 9 contrôles de routage ; pas encore d'effets sur les bus ni d'interface : phase 4). Aujourd'hui `mixTracks` somme les pistes. Introduire une structure « nœuds → connexions » (source, piste, bus, master) en C++ *sans changer le comportement*, avec tests hors ligne comparant le rendu avant/après (bit-exact). Prérequis des phases 4 et 5.
- [x] **0.6 Banc de tests hors ligne étendu** (fait : `native/dsptest/run_engine.sh`, moteur réel compilé sur PC avec un stub d'Oboe, 7 scènes d'or, mode `--strict` bit-exact, détection de non-déterminisme ; voir `docs/testing.md`) (`native/dsptest`) : rendu d'un projet de référence, sommes de contrôle, mesure de pics/RMS pour détecter les régressions audio sans oreille.

Risques : le graphe de rendu touche la boucle audio ; tout changement doit rester sans allocation ni verrou côté callback.

---

## Phase 1 — Piano roll : vélocité et outils de notes

But : gains visibles rapides, sans changer le modèle de projet (sauf champs de note supplémentaires, migration 0.3).

- [x] **1.1 Éditeur de vélocité** (0.50.0 ; vérifié sur S10 : glisser une tige, undo. Reste : pan/cutoff… voir 1.2, hauteur réglable) en bas du piano roll (barres par note, glisser pour modifier, tracé à main levée, sélection liée à celle des notes, redimensionnable). Les notes sélectionnées s'éditent ensemble.
- [x] **1.2 Propriétés par note** (0.51.0 ; format de projet 3, ZkEvent 16 octets / pont version 5 ; Hybrid, Sampler, Drums, Piano ; Grain non. Mesuré hors ligne sur Hybrid et Drums (pan, hauteur, coupure, release) ; Sampler et Piano non mesurés (échantillon / banque nécessaires) ; pas écouté) : pan, cutoff, résonance, accord fin (fine pitch), longueur de release. Les sélecteurs de contrôleur de l'éditeur du bas (vélocité, pan, cutoff…). Transmis aux instruments natifs via de nouveaux champs de `ZkEvent`.
- [x] **1.3 Ghost notes** (0.52.0, vérifié S10) : voir les notes des autres pistes (atténuées), option par piste.
- [x] **1.4 Quantize avancé** (0.52.0 : grille, début/fin, force, swing, fenêtre ; tests unitaires) : force (%), fenêtre, quantize des débuts / fins, swing, grille irrégulière.
- [x] **1.5 Humanize** (0.52.0) : décalage temporel, vélocité, longueur (aléatoire graduée, graine fixe pour reproductibilité).
- [x] **1.6 Strum / « aiguiser »** (0.52.0) : étaler un accord sur une durée, sens montant/descendant, courbe de tension.
- [x] **1.7 Arpégiateur d'édition** (0.52.0) (transformation, pas temps réel) : motifs, octaves, gate, swing ; applique sur la sélection.
- [x] **1.8 Chord stamps et détection d'accords** (0.52.0) : bibliothèque d'accords (selon la gamme du projet), tampon d'accord sur un tap, nom de l'accord affiché pour la sélection.
- [x] **1.9 Slide notes et portamento** (0.53.0 : Hybrid + Sampler ; le temps du portamento = paramètre Glide, au moins 70 ms ; mesuré hors ligne) : note « slide » qui glisse en hauteur vers la suivante ; paramètre de portamento par note ; support dans les instruments natifs (Hybrid, Piano exclus s'il n'y a pas de sens).
- [x] **1.10 Riff machine** (0.52.0) : générateur de motifs basé sur la gamme (rythme + hauteurs), régénérable, avec verrouillage de paramètres.
- [x] **1.11 LFO d'événements** (0.52.0) (modulation de vélocité/pan/cutoff sur une plage de notes) : outil de génération de courbes.
- [x] **1.12 Stamp/pinceau** (0.52.0 : pinceau de notes) : poser en continu des notes ou des motifs en glissant.

Critères : chaque outil a son test hors ligne (ou test unitaire Kotlin) sur l'entrée/sortie des notes ; les outils sont annulables.

Risques : beaucoup de petites interfaces tactiles ; garder une barre d'outils contextuelle claire, pas un menu géant.

---

## Phase 2 — Patterns et Playlist

But : le cœur du workflow FL. C'est le plus gros changement de modèle.

### Conception (à valider avant de coder)
- Un **pattern** = un ensemble de **canaux** (instrument + notes ou audio) avec une longueur ; les notes appartiennent au pattern, pas à la piste.
- La **Playlist** = pistes (lignes) contenant des **clips** : clip-pattern (référence à un pattern), clip audio, clip d'automation. Plusieurs clips peuvent référencer le même pattern (modifier le pattern modifie toutes les copies). Option « rendre unique » (clone indépendant).
- **Channel Rack** : liste des canaux (instruments natifs/VST + samples) avec volume, pan, mute/solo, routage vers une piste de mixer (voir phase 4), et **step sequencer** (grille 16/32/64 pas, vélocité par pas, tuple pour notes).
- Migration : chaque piste existante devient un canal ; ses notes forment un pattern « Pattern 1 » placé en clips ; le rendu doit rester **identique** (test bit-exact).

### Tâches
- [x] **2.1 Modèle de données** (0.54.0 ; format 4) (`DawModel.kt`) : `Pattern`, `Channel`, `PatternClip`, migration, sérialisation, tests de chargement d'anciens projets.
- [x] **2.2 Moteur** (0.54.0 : notes dérivées de la playlist, moteur inchangé) : lecture des notes d'un pattern à partir de la position des clips (compensation de longueur, boucle de pattern, pattern plus court/long que le clip), sans allocation dans le callback.
- [x] **2.3 Channel Rack + step sequencer** (0.55.0 : grille de pas, pads de Drums, peinture ; sans vélocité par pas ni tuplets) : grille de pas tactile, vélocité par pas, glisser pour peindre, copier/coller de pas, aperçu au toucher.
- [x] **2.4 Sélecteur de pattern** (0.54.0) (liste, renommer, couleur, dupliquer, supprimer, fusionner, diviser).
- [x] **2.5 Playlist** (0.54.0–0.55.0 : voie de patterns, mode Song / Pattern ; pas de pistes de clips audio mêlées aux patterns : les clips audio restent sur leurs pistes) : placement de clips-pattern par glisser, étirement (répétition ou coupe), clone, rendu unique, couleurs, mode **Song / Pattern** (lecture d'un pattern seul en boucle ou de la chanson entière).
- [x] **2.6 Piano roll sur un canal d'un pattern** (0.54.0) (le piano roll existant devient l'éditeur du canal courant du pattern courant).
- [x] **2.7 Clips de pattern liés** (0.54.0 : Make unique ; édition liée par construction) : indicateur visuel, « Make unique », « Select all instances ».
- [ ] **2.8 Navigateur** : patterns et canaux dans le sound browser.
- [x] **2.9 Performance** (0.55.0 : calcul pur de 40 canaux × 100 placements en < 2 s testé ; ouverture/lecture d'un gros projet non mesurées sur téléphone) : projets de 100 patterns / 40 canaux ; temps d'ouverture et de lecture mesurés.

Critères : un projet existant s'ouvre et sonne exactement pareil ; un beat 16 pas se fait en moins de 30 s ; modifier un pattern met à jour tous ses clips.

Risques : migration du format, régressions d'édition sur arrangement/piano roll, ergonomie sur petit écran (le Channel Rack doit rester utilisable au pouce).

---

## Phase 3 — Marqueurs, carte de tempo et de signature

- [x] **3.1 Marqueurs** (0.56.0) de la timeline : nom, couleur, saut de lecture, liste ; marqueurs de **tempo** et de **signature** (insertion à une mesure donnée).
- [x] **3.2 Carte de tempo** (0.56.0 : clips, notes, automation, boucle, curseur et plugins suivent la carte ; vérifié sur S10 : 100 → 150 BPM à la mesure 3) (BPM par segment, rampes de tempo) : le moteur convertit temps ↔ position en échantillons par la carte ; la lecture, l'enregistrement, l'export et l'étirement de clips audio (« Fit to project BPM ») l'utilisent.
- [~] **3.3 Signatures variables (0.56.0 : lignes de mesure et numéros de la règle de l'arrangement ; piano roll, rack et patterns comptent toujours des mesures de 4 temps ; métronome et « Fit to bar » non adaptés)** (4/4, 3/4, 7/8, 6/8 …) : règle, grille, début de mesure, métronome, « Fit to bar » selon la signature courante.
- [~] **3.4 Transmission aux plugins** (0.56.0 : tempo et position par segment ; signature toujours 4/4) (compléter 0.1/0.2 par segment).
- [x] **3.5 Interface** (0.56.0 : voie Markers · Tempo) : piste de tempo / de marqueurs en haut de la Playlist, édition tactile.

Critères : un projet à tempo variable s'exporte avec les bons points d'échantillon (mesure de position de pics hors ligne) ; changer la signature ne casse pas les clips existants.

Risques : tout ce qui suppose un BPM unique (étirement des clips, automation en battements) doit passer par la carte de tempo ; faire l'inventaire des usages avant de commencer (`grep` sur `bpm`/`ppb`).

---

## Phase 4 — Routage du mixer

Prérequis : 0.5 (graphe de rendu).

- [~] **4.1 Pistes de mixer indépendantes (0.57.0 : bus, sorties de piste ; pas les canaux du Rack vers n'importe quelle piste)** (canaux du Channel Rack → n'importe quelle piste du mixer), bus/groupes, master.
- [~] **4.2 Sends (0.57.0 : envois pré/post-fader, 4 par piste, niveau par envoi ; pas encore d'effets sur les bus ni de sidechain)** : niveau d'envoi par piste vers un bus (pré/post fader), plusieurs envois, bus d'effets partagés (reverb/delay).
- [x] **4.3 Groupes (0.57.0)** : sous-mix (la sortie d'une piste va vers un bus plutôt que le master) ; routage libre sans boucle (détection de cycle, message d'erreur).
- [ ] **4.4 Sidechain** : entrée latérale d'un effet (native et VST3 quand le plugin l'expose) ; sélection de la source dans l'interface de l'insert.
- [ ] **4.5 Compensation de latence des plugins (PDC)** : lire la latence rapportée par chaque plugin (VST3 `getLatencySamples`, hôtes Wine), aligner toutes les voies sur la plus longue, avec option de désactivation ; affichage de la latence totale.
- [ ] **4.6 Freeze / render de piste** : rendre une piste (plugins compris) en audio, la remplacer par un clip audio gelé, dégeler pour retrouver les plugins ; cache dans `Cache/Freeze`.
- [~] **4.7 Interface (0.57.0 : bouton de sortie, dialogue d'envois, bandes de bus ; pas de glisser pour router ni de groupes repliables)** : vue mixer avec sélecteur de sortie/envois par piste, indicateurs de routage, glisser pour router, groupes repliables.
- [ ] **4.8 Stems** (préparation) : le graphe sait rendre une piste ou un bus seul.

Critères : tests hors ligne : somme de bus = somme des pistes ; PDC : deux voies avec un plugin de latence connue s'alignent à l'échantillon ; freeze produit un rendu identique à la lecture normale (à la tolérance près).

Risques : latence et CPU des hôtes Wine (≈40 % d'un cœur chacun) : plafonner le nombre d'hôtes, prévoir un avertissement.

---

## Phase 5 — Effets natifs de mixage et mesure

Tous natifs (`native/dsp/*`), enregistrés dans `registry.cpp`, avec presets et le même navigateur que les instruments.

- [ ] **5.1 EQ paramétrique** (8 bandes, types : cloche, étagères, passe-haut/bas, notch, pente 12–48 dB/oct, mode stéréo/M-S, analyse spectrale intégrée, glisser des nœuds au doigt).
- [ ] **5.2 Compresseur** (seuil, ratio, attaque, release, genou, makeup, sidechain interne et externe, mode peak/RMS, mélange parallèle, courbe de réduction de gain).
- [ ] **5.3 Limiteur** (lookahead, plafond, release automatique, indicateur de réduction).
- [ ] **5.4 Reverb** de qualité (algorithmique : FDN/Dattorro ; pré-délai, taille, amortissement, diffusion, mod).
- [ ] **5.5 Delay** (sync tempo via carte de tempo, ping-pong, feedback filtré, ducking).
- [ ] **5.6 Saturation / distorsion** (plusieurs courbes, oversampling 2×/4×, tone, mix).
- [ ] **5.7 Filtre** (LP/HP/BP/notch/peigne, pentes, enveloppe suiveuse, LFO sync).
- [ ] **5.8 Autres effets utiles** : chorus/flanger/phaser, tremolo, bitcrusher, transient shaper, gate, de-esser, widener (utilise déjà la roue stéréo), pitch shifter simple.
- [ ] **5.9 Panneau de mesure** (sur le master et par piste) : analyseur de spectre (FFT, lissage, échelle), **loudness** (momentané, court terme, intégré, LRA, true-peak, selon ITU-R BS.1770 / EBU R128), **vectorscope / corrélation de phase**, oscilloscope.
- [ ] **5.10 Intégration** : ces effets apparaissent dans la liste des inserts et des bus ; cohérence avec les presets natifs (étoile, aperçu).

Critères : chaque effet validé hors ligne (réponse en fréquence, courbe de compression, gain de réduction, true-peak) ; mesures LUFS comparées à un signal de référence (ex. sinus à -23 LUFS) ; aucune allocation dans le callback.

Risques : coût CPU des effets sur appareils anciens (mesurer avec `xruns`) ; oversampling optionnel.

---

## Phase 6 — Clips audio

### 6A. Éditions de base
- [ ] **6.1 Fondus d'entrée/sortie** sur chaque clip (poignées sur le clip), formes (linéaire, exponentielle, S, constante en puissance).
- [ ] **6.2 Crossfades automatiques** entre clips qui se chevauchent sur la même piste.
- [ ] **6.3 Courbe de gain du clip** (enveloppe de volume dessinée sur le clip, points et segments, annulable).
- [ ] **6.4 Découpage** (couper à la position, couper en grille, couper aux marqueurs) et **tranchage** par détection de transitoires (voir 6.7).

### 6B. Time-stretch de haute qualité
- [ ] **6.5 Moteur de stretch** meilleur que le WSOLA actuel. **Élastique est propriétaire (licence commerciale)** : ne pas l'utiliser. Options : **Rubber Band** (GPL ou licence commerciale ; le GPL imposerait la même licence au binaire), **SoundTouch** (LGPL), ou implémentation maison (phase vocoder avec préservation des transitoires + verrouillage de phase). *À décider avec toi avant de coder : voir Questions ouvertes.*
- [ ] **6.6 Modes** : rythmique (transitoires préservés), tonal/polyphonique, voix, formant préservé ou non ; prévisualisation, rendu mis en cache comme aujourd'hui.

### 6C. Edison-lite (éditeur audio complet)
- [ ] **6.7 Éditeur audio** plein écran : forme d'onde, sélection, zoom, lecture, copier/couper/coller/supprimer, silence, inverser, normaliser, fondus, gain, DC offset.
- [ ] **6.8 Vue spectrale** (spectrogramme), sélection fréquentielle.
- [ ] **6.9 Nettoyage du bruit** (profil de bruit + soustraction spectrale, gate spectral), **de-click**, passe-haut anti-ronflement.
- [ ] **6.10 Détection de transitoires et tranchage** en régions, export des tranches vers le Sampler (slices sur les touches) ou en clips.
- [ ] **6.11 Enregistrement dans l'éditeur**, boucle, marqueurs et points de boucle.

### 6D. Correction de hauteur (type Newtone)
- [ ] **6.12 Analyse mono** (YIN/pYIN) en notes ; affichage type piano roll.
- [ ] **6.13 Édition** : déplacer chaque note détectée en hauteur, décaler, couper, lisser la transition, vibrato, formant ; **correction automatique vers la gamme du projet**, force, vitesse de retune.
- [ ] **6.14 Rendu** (pitch-shift par segment avec préservation du formant, cache).

Critères : mesures hors ligne (durée, hauteur détectée avant/après, absence de clics aux jonctions) ; fondus sans discontinuité ; édition non destructive (les originaux restent dans `Samples/`, les rendus dans `Cache`).

Risques : qualité du stretch/pitch (subjectif, vérifier à l'oreille côté utilisateur) ; mémoire pour les spectrogrammes de gros fichiers (calcul par blocs et mise en cache).

---

## Phase 7 — Automation et modulation

- [ ] **7.1 Clips d'automation** : un clip d'automation placé dans la Playlist, réutilisable (liés comme les patterns), s'applique à n'importe quel paramètre (VST, natif, volume, pan, largeur…).
- [ ] **7.2 Courbes avancées** : tension réglable, hold, double-courbe, sinus, rampes, magnétisme à la grille ; conversion line/step/smooth existante.
- [ ] **7.3 Liens entre contrôles** (« Link to controller ») : un contrôle pilote un autre avec plage, inversion, courbe.
- [ ] **7.4 Contrôleurs internes** :
  - **LFO / Formula** : expressions (sin, rampes, aléa), sync tempo, plage ;
  - **Envelope controller** : suit un déclencheur (note, début de clip) ;
  - **Peak controller** : suit le niveau d'une piste (ducking/sidechain « à l'ancienne »).
- [ ] **7.5 Enregistrement d'automation** depuis le mouvement des potars en lecture (REC existe : étendre aux clips et aux contrôleurs).
- [ ] **7.6 Interface** : liste des contrôleurs liés, indicateurs sur les potars, édition tactile.

Critères : l'automation suit la carte de tempo (en battements) ; test hors ligne : un LFO de 1/4 à 120 BPM = 2 Hz.

Risques : cohérence avec l'automation existante par paramètre (migration 0.3) ; le coût CPU de nombreux contrôleurs.

---

## Phase 8 — Formats de sons

- [ ] **8.1 SoundFont 2 (.sf2)** : lecture complète (zones, générateurs, modulateurs, boucles, sample-rate) par un moteur natif ; base candidate : **TinySoundFont** (MIT) ou implémentation maison.
- [ ] **8.2 SFZ complet** : étendre l'import actuel (régions, groupes, `lokey/hikey/lovel/hivel`, `loop_*`, `ampeg_*`, `fil_*`, `cutoff`, `lfo`, `trigger`, `group/off_by`, `sw_*` key-switches, `seq_*` round-robin, `#include`/`#define`, opcodes de modulation courants). Test sur des banques libres.
- [ ] **8.3 Decent Sampler** (`.dspreset`, XML ouvert) : groupes, samples, boucles, enveloppes, effets simples, UI de potars ; c'est un format documenté et libre, donc faisable.
- [ ] **8.4 Banques Kontakt (.nki/.nkx/.nkc)** : **non faisable** en l'état (format propriétaire, souvent chiffré ; usage hors Kontakt non autorisé). Proposition : lire uniquement les **samples WAV non chiffrés** d'une banque (si l'utilisateur les a) et fournir un assistant de cartographie manuelle vers le Sampler. À confirmer.
- [ ] **8.5 CLAP** : hôte CLAP **natif Android** (plugins `.clap` compilés pour ARM64 Android ; peu nombreux aujourd'hui) **et** hôte CLAP Windows via Wine + Box64 comme pour VST3 (même architecture mémoire partagée). Spécification MIT : légal. Étapes : lecture de l'entrée `clap_entry`, paramètres, notes/événements, GUI, état, thread-check.
- [ ] **8.6 Navigateur** : formats reconnus dans la bibliothèque, présets de banques, aperçu.

Critères : une banque SFZ/SF2/Decent chargée dans le Sampler joue au bon hauteur/vélocité/boucle (mesures hors ligne) ; un plugin CLAP de test charge, expose ses paramètres, et rend de l'audio.

Risques : la diversité des opcodes SFZ (couvrir les 90 % courants, lister clairement ce qui est ignoré) ; CLAP natif Android rare ; temps de chargement de grosses banques (streaming comme pour le piano).

---

## Phase 9 — Interopérabilité

Objectif de l'utilisateur : « pouvoir continuer sur FL Studio Windows ».

- [ ] **9.1 Export MIDI** (`.mid`) : un fichier multi-pistes (une piste par canal/pattern ou arrangé), tempo et signature de la carte, noms de pistes, marqueurs.
- [ ] **9.2 Import MIDI** : tempo, signatures, pistes → canaux/patterns, quantification optionnelle.
- [ ] **9.3 Export de stems** : une piste/bus par fichier (WAV/FLAC), mêmes réglages d'export que l'audio actuel, plage entière ou boucle, avec ou sans effets de master, nommage `Projet - Piste.wav`.
- [ ] **9.4 Export multi-pistes pour FL** : dossier `Projet_FL/` avec stems + MIDI + `README.txt` (tempo, signature, instruments d'origine) : **solution robuste et immédiate** pour continuer sous FL.
- [ ] **9.5 Export FLP (projet FL Studio)** : le format est propriétaire et non documenté officiellement ; il existe des analyses publiques (rétro-ingénierie) mais **sans garantie** et il évolue à chaque version. Plan réaliste en paliers :
  - palier 1 : lire des FLP simples (tempo, patterns, notes, noms de canaux, mixer de base) pour l'import ;
  - palier 2 : écrire un FLP **minimal** (tempo, patterns avec notes, playlist, noms) ouvrable dans une version donnée de FL ;
  - palier 3 : plugins/paramètres : **non garanti** (état binaire des plugins).
  Tout est testé par l'utilisateur sur FL Studio Windows (je n'y ai pas accès) : on avance palier par palier avec ses retours.
- [ ] **9.6 Export/import par dossier de projet** (zip du projet : `.zedok` + `Samples/` + `Presets/`) pour transfert PC/téléphone, avec chemins relatifs.
- [ ] **9.7 Dither et normalisation à l'export** (TPDF, noise-shaped), arrondi propre en 16 bits.

Critères : MIDI exporté ouvert dans un lecteur indépendant, mêmes notes et mêmes positions (comparaison automatique par script) ; stems resomme exactement au rendu master (tolérance de 1 LSB) ; FLP : réussite si FL le charge sans erreur (vérifié par l'utilisateur).

Risques : FLP (légal : rétro-ingénierie d'interopérabilité, ne pas copier de code ou de ressources FL) ; changements de version de FL ; plugins non transférables.

---

## Phase 10 — Finition et dette

- [ ] **10.1 Performances** : profilage (CPU moteur, mémoire, temps de chargement), seuils par appareil.
- [ ] **10.2 Documentation** : README, `docs/`, aide dans l'application, exemples de projets.
- [ ] **10.3 Raccourcis clavier configurables** (clavier physique/BT), curseurs de défilement.
- [ ] **10.4 Accessibilité et ergonomie** : tailles tactiles, retours haptiques, thèmes.
- [ ] **10.5 Tests** : suite hors ligne exécutable d'une commande, scénarios de non-régression audio, projets témoins.
- [ ] **10.6 Autres idées FL** (selon retours) : piste vidéo, mastering, MPE, CC/MIDI learn, MIDI out, sync d'horloge, tempo tap.

---

## Questions ouvertes (à trancher quand on arrivera à la phase concernée)

1. **Time-stretch HQ** (6.5) : Rubber Band en GPL (le binaire du projet devient GPL, donc le code doit rester ouvert), SoundTouch en LGPL, ou un moteur maison ? Le dépôt a une licence MIT/autre ? À confirmer avant de choisir.
2. **Kontakt** (8.4) : accepter l'option « samples WAV + cartographie manuelle » ?
3. **FLP** (9.5) : lecture seule d'abord, ou écriture directement ? Quelle version de FL Studio utilises-tu (pour tester) ?
4. **Channel Rack** (2.3) : disposition portrait/paysage ; préférence pour un panneau latéral ou une vue plein écran ?
5. **Limites CPU** : plafond d'hôtes Wine simultanés à imposer (4 ?) pour garder la lecture stable sur S10.

## Journal d'avancement

| Version | Date | Contenu |
|---|---|---|
| 0.57.0-alpha | 2026-11-28 | Phase 4 (1re partie) : bus, sorties de piste, envois pré/post ; format 6. Non vérifié sur le S10 (téléphone non connecté) |
| 0.56.0-alpha | 2026-11-27 | Phase 3 : marqueurs, carte de tempo, signatures (règle) ; format 5 |
| 0.55.0-alpha | 2026-11-27 | Phase 2 : Rack (step sequencer), mode Song / Pattern. Reste de la phase 2 : navigateur (2.8), voir feuille |
| 0.54.0-alpha | 2026-11-27 | Phase 2 (1re partie) : patterns, playlist (voie de patterns), format 4 |
| 0.53.0-alpha | 2026-11-27 | Phase 1.9 : slide notes (Hybrid, Sampler). Phase 1 terminée |
| 0.52.0-alpha | 2026-11-27 | Phase 1 : ghost notes, pinceau, accords, quantize, humanize, strum, arpège, riff, LFO d'événements (menu Tools) |
| 0.51.0-alpha | 2026-11-27 | Phase 1.2 : propriétés par note (pan, cutoff, résonance, hauteur fine, release), format 3 |
| 0.50.0-alpha | 2026-11-27 | Phase 1.1 : éditeur de vélocité sous le piano roll |
| 0.49.9-alpha | 2026-11-27 | Phase 0.5 : graphe de routage du moteur (bus), rendu inchangé bit pour bit ; phase 0 terminée |
| 0.49.8-alpha | 2026-11-27 | Phase 0.4 : undo/redo unique pour tout le projet (File → Undo/Redo, pile nommée de 100 actions) |
| 0.49.7-alpha | 2026-11-27 | Phase 0.3 : format de projet versionné (migrations, refus des fichiers plus récents, tests avec projets témoins) |
| 0.49.6-alpha | 2026-11-27 | Phase 0.1/0.2 : tempo, position, début de mesure et état de lecture transmis aux plugins Windows (VST2 mesuré, VST3 non mesuré) |

## Notes d'architecture de référence (pour reprendre après une coupure)

- Moteur : `app/src/main/cpp/engine.{h,cpp}` (mixTracks, largeur stéréo, declick/fade-in), `plugin_bridge.h` (hôte natif et Wine), `native/dsp/*.h` (processeurs natifs, `registry.cpp`).
- Modèle : `model/DawModel.kt`, `DawViewModel.kt` (attention à l'ordre d'initialisation : `load()` est appelé depuis le constructeur), `ProjectStore.kt`.
- UI : `ui/PianoRoll.kt`, `ArrangeView.kt`, `MixerView.kt`, `ClipEditor.kt`, `nplug/*`.
- Outils hors ligne : `native/dsptest` (render, fuzz), `native/presets/build.sh`.
- Mémoire de session : `~/.claude/projects/-home-david-dev-android-ZedokMusic/memory/`.
