package org.example.tutorial;

import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Husk;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Wolf;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.EulerAngle;

import java.util.List;

/**
 * Scénario de la visite guidée.
 *
 * <p>Les délais sont exprimés en ticks afin que les explications restent
 * lisibles sans bloquer le thread serveur. Chaque chapitre reçoit un jeton :
 * quitter /tuto invalide immédiatement toutes ses actions restantes.</p>
 */
final class TutorialTourDirector {

    private static final int Y = TutorialWorldGenerator.WALK_Y;

    private final TutorialManager manager;

    TutorialTourDirector(TutorialManager manager) {
        this.manager = manager;
    }

    void start(TutorialSession session) {
        int phase = manager.beginPhase(session);
        manager.chapter(
                session,
                "Bienvenue dans le tutoriel",
                "Ton guide : un robot, beaucoup trop de magie",
                0.03D
        );

        manager.later(session, phase, 10L, () ->
                manager.speak(
                        session,
                        "Bip-boup et bienvenue sur §6MineGus§f ! "
                                + "Moi, c'est §bDonjBot§f : guide certifié, "
                                + "magicien presque certifié."
                )
        );
        manager.later(session, phase, 65L, () ->
                manager.speak(
                        session,
                        "Je vais jouer devant toi comme un vrai joueur et te "
                                + "montrer les commandes une par une. Sans "
                                + "tricher... enfin, presque."
                )
        );
        manager.later(session, phase, 125L, () ->
                manager.speak(
                        session,
                        "Reste à moins de §b"
                                + manager.recallDistanceBlocks()
                                + " blocs§f de mes boulons. Si tu te perds, "
                                + "mon rayon anti-touriste te ramènera."
                )
        );
        manager.later(session, phase, 180L, () ->
                manager.speak(
                        session,
                        "Et §6/tuto§f quitte instantanément la visite pour te "
                                + "remettre exactement là où tu étais. Prêt ? "
                                + "On commence par du très gros !"
                )
        );
        manager.later(session, phase, 235L, () ->
                villageChapter(session)
        );
    }

    private void villageChapter(TutorialSession session) {
        int phase = manager.beginPhase(session);
        manager.chapter(
                session,
                "/village",
                "Un village complet en une commande",
                0.11D
        );

        manager.later(session, phase, 5L, () ->
                manager.speak(
                        session,
                        "La commande §6/village§f construit une implantation "
                                + "complète : bâtiments, routes, habitants, "
                                + "défenses et services."
                )
        );
        manager.later(session, phase, 60L, () ->
                manager.speak(
                        session,
                        "Elle travaille progressivement pour ménager le "
                                + "serveur. Et si la dernière construction ne "
                                + "te plaît pas, §6/village undo§f permet de "
                                + "l'annuler."
                )
        );
        manager.later(session, phase, 120L, () -> {
            manager.showCommand(session, "/village");
            manager.applyScene(
                    session,
                    TutorialSceneBuilder.villageScene(),
                    success -> {
                        if (!session.isPhase(phase)) {
                            return;
                        }
                        if (!success) {
                            manager.failSession(
                                    session,
                                    "La miniature du village n'a pas pu être "
                                            + "construite."
                            );
                            return;
                        }
                        revealVillage(session, phase);
                    }
            );
        });
    }

    private void revealVillage(TutorialSession session, int phase) {
        World world = session.world();
        Location reveal = new Location(world, 24.5D, Y + 3.0D, 0.5D);
        manager.magicalEffect(session, reveal);

        Location botLocation =
                TutorialSceneBuilder.villageNpcLocation(world);
        Location playerLocation =
                TutorialSceneBuilder.Station.VILLAGE.location(world);
        if (!manager.teleportPair(
                session,
                botLocation,
                playerLocation
        )) {
            manager.failSession(
                    session,
                    "Le saut magique vers le village a été refusé."
            );
            return;
        }
        session.station(TutorialSceneBuilder.Station.VILLAGE);
        spawnVillagePopulation(session);

        manager.later(session, phase, 20L, () ->
                manager.speak(
                        session,
                        "Tadaaam ! Un vrai village de poche. Le tien sera "
                                + "beaucoup plus ambitieux ; ici je garde la "
                                + "version transportable en cabine."
                )
        );
        manager.later(session, phase, 75L, () ->
                manager.speak(
                        session,
                        "Les PNJ et gardes ne sont pas juste décoratifs : "
                                + "MineGus suit leur rôle et protège la "
                                + "population créée."
                )
        );
        manager.later(session, phase, 130L, () ->
                beginWalk(
                        session,
                        phase,
                        TutorialSceneBuilder.Station.MINE,
                        "Direction la carrière ! Suis mes petites jambes "
                                + "télescopiques.",
                        () -> mineChapter(session)
                )
        );
    }

    private void mineChapter(TutorialSession session) {
        int phase = manager.beginPhase(session);
        manager.chapter(
                session,
                "/mineur",
                "Une carrière automatisée et pilotable",
                0.24D
        );

        manager.later(session, phase, 5L, () ->
                manager.speak(
                        session,
                        "Petite précision de robot pointilleux : la commande "
                                + "s'appelle §6/mineur§f, pas /mine. Elle te "
                                + "donne un bâton pour sélectionner deux coins."
                )
        );
        manager.later(session, phase, 65L, () ->
                manager.speak(
                        session,
                        "Avant de valider la sélection, choisis le métier avec "
                                + "§6/job mineur§f. Ensuite, le mineur creuse, "
                                + "range les ressources et accepte plusieurs "
                                + "modes : carrière, branches, tunnel ou filons."
                )
        );
        manager.later(session, phase, 115L, () ->
                manager.showCommand(session, "/job mineur")
        );
        manager.later(session, phase, 155L, () -> {
            manager.showCommand(session, "/mineur");
            manager.applyScene(
                    session,
                    TutorialSceneBuilder.mineScene(),
                    success -> {
                        if (!session.isPhase(phase)) {
                            return;
                        }
                        if (!success) {
                            manager.failSession(
                                    session,
                                    "La carrière de démonstration a échoué."
                            );
                            return;
                        }
                        revealMine(session, phase);
                    }
            );
        });
    }

    private void revealMine(TutorialSession session, int phase) {
        World world = session.world();
        manager.magicalEffect(
                session,
                new Location(world, 81.0D, Y + 2.0D, -7.0D)
        );

        Villager miner = spawnVillager(
                session,
                new Location(world, 71.5D, Y, -11.5D, 90.0F, 0.0F),
                "§6Mineur automatique",
                Villager.Profession.TOOLSMITH
        );
        IronGolem guard = spawnGolem(
                session,
                new Location(world, 91.5D, Y, -9.5D),
                "§7Garde de la carrière"
        );
        if (miner != null) {
            manager.sparkle(
                    session,
                    miner.getEyeLocation(),
                    Particle.HAPPY_VILLAGER,
                    18
            );
        }
        if (guard != null) {
            manager.sparkle(
                    session,
                    guard.getLocation().add(0.0D, 1.4D, 0.0D),
                    Particle.END_ROD,
                    12
            );
        }

        manager.later(session, phase, 25L, () ->
                manager.speak(
                        session,
                        "Le mineur est là, le stockage aussi, et un golem "
                                + "surveille les outils. Observe le minerai "
                                + "dans la paroi..."
                )
        );
        manager.later(session, phase, 70L, () -> {
            manager.breakBlockDemo(
                    session,
                    new Location(
                            world,
                            76.0D,
                            TutorialWorldGenerator.GROUND_Y - 4.0D,
                            -7.0D
                    )
            );
            manager.speak(
                    session,
                    "Crac ! Les blocs sont traités proprement, les drops "
                            + "calculés puis routés vers les conteneurs."
            );
        });
        manager.later(session, phase, 130L, () ->
                manager.speak(
                        session,
                        "§6/mineur list§f, §6/mineur select§f, "
                                + "§6/mineur pause§f, §6/mineur resume§f, "
                                + "§6/mineur stop§f et §6/mineur info§f "
                                + "pilotent tes sessions. §6/mineur trust§f "
                                + "partage l'accès avec un autre joueur."
                )
        );
        manager.later(session, phase, 205L, () ->
                beginWalk(
                        session,
                        phase,
                        TutorialSceneBuilder.Station.FARM,
                        "On laisse notre mineur travailler. Prochaine étape : "
                                + "la ferme qui n'oublie jamais d'arroser.",
                        () -> farmChapter(session)
                )
        );
    }

    private void farmChapter(TutorialSession session) {
        int phase = manager.beginPhase(session);
        manager.chapter(
                session,
                "/champ",
                "Agriculture automatique",
                0.36D
        );

        manager.later(session, phase, 5L, () ->
                manager.speak(
                        session,
                        "§6/champ§f fournit lui aussi un bâton de sélection. "
                                + "Clique deux blocs à la même hauteur pour "
                                + "délimiter la parcelle."
                )
        );
        manager.later(session, phase, 65L, () ->
                manager.speak(
                        session,
                        "Le système prépare le terrain, plante, récolte puis "
                                + "replante. Un fermier et ses golems veillent "
                                + "sur la production."
                )
        );
        manager.later(session, phase, 125L, () -> {
            manager.showCommand(session, "/champ");
            manager.applyScene(
                    session,
                    TutorialSceneBuilder.farmScene(),
                    success -> {
                        if (!session.isPhase(phase)) {
                            return;
                        }
                        if (!success) {
                            manager.failSession(
                                    session,
                                    "Le champ de démonstration a échoué."
                            );
                            return;
                        }
                        revealFarm(session, phase);
                    }
            );
        });
    }

    private void revealFarm(TutorialSession session, int phase) {
        World world = session.world();
        manager.magicalEffect(
                session,
                new Location(world, 121.0D, Y + 1.0D, 5.0D)
        );
        Villager farmer = spawnVillager(
                session,
                new Location(world, 118.5D, Y, 14.5D),
                "§aFermier automatique",
                Villager.Profession.FARMER
        );
        spawnGolem(
                session,
                new Location(world, 132.5D, Y, 14.5D),
                "§7Protecteur des récoltes"
        );

        manager.later(session, phase, 20L, () -> {
            if (farmer != null) {
                manager.sparkle(
                        session,
                        farmer.getEyeLocation(),
                        Particle.HAPPY_VILLAGER,
                        22
                );
            }
            for (int x = 111; x <= 131; x += 4) {
                manager.sparkle(
                        session,
                        new Location(world, x + 0.5D, Y + 0.4D, 4.5D),
                        Particle.HAPPY_VILLAGER,
                        8
                );
            }
            manager.speak(
                    session,
                    "Blé, carottes, pommes de terre... la parcelle est "
                            + "mûre et le stockage attend la récolte. Aucun "
                            + "réveil à six heures requis."
            );
        });
        manager.later(session, phase, 105L, () ->
                beginWalk(
                        session,
                        phase,
                        TutorialSceneBuilder.Station.FOREST,
                        "Cap sur la forêt. Attention, mon détecteur à écureuils "
                                + "est très enthousiaste.",
                        () -> forestChapter(session)
                )
        );
    }

    private void forestChapter(TutorialSession session) {
        int phase = manager.beginPhase(session);
        manager.chapter(
                session,
                "/foret",
                "Bois renouvelable et forestier persistant",
                0.48D
        );

        manager.later(session, phase, 5L, () ->
                manager.speak(
                        session,
                        "§6/foret§f commence par deux coins au même niveau. "
                                + "MineGus construit ensuite une micro-forêt "
                                + "avec cabane, éclairage et stockage."
                )
        );
        manager.later(session, phase, 65L, () ->
                manager.speak(
                        session,
                        "Le forestier coupe du haut vers le bas, récupère les "
                                + "drops et replante le bon type de pousse. "
                                + "C'est le cycle du bois, version organisée."
                )
        );
        manager.later(session, phase, 130L, () -> {
            manager.showCommand(session, "/foret");
            manager.applyScene(
                    session,
                    TutorialSceneBuilder.forestScene(),
                    success -> {
                        if (!session.isPhase(phase)) {
                            return;
                        }
                        if (!success) {
                            manager.failSession(
                                    session,
                                    "La forêt de démonstration a échoué."
                            );
                            return;
                        }
                        revealForest(session, phase);
                    }
            );
        });
    }

    private void revealForest(TutorialSession session, int phase) {
        World world = session.world();
        manager.magicalEffect(
                session,
                new Location(world, 162.0D, Y + 3.0D, 26.0D)
        );
        Villager forester = spawnVillager(
                session,
                new Location(world, 151.5D, Y, 20.5D),
                "§2Forestier automatique",
                Villager.Profession.FLETCHER
        );
        spawnGolem(
                session,
                new Location(world, 174.5D, Y, 38.5D),
                "§7Garde forestier"
        );

        manager.later(session, phase, 25L, () -> {
            if (forester != null) {
                manager.sparkle(
                        session,
                        forester.getEyeLocation(),
                        Particle.HAPPY_VILLAGER,
                        18
                );
            }
            manager.sparkle(
                    session,
                    new Location(world, 166.5D, Y + 4.0D, 36.5D),
                    Particle.COMPOSTER,
                    24
            );
            manager.speak(
                    session,
                    "Plusieurs essences, des pousses prêtes et un tas de bois "
                            + "rangé. Mon côté robot apprécie énormément les "
                            + "tas bien rangés."
            );
        });
        manager.later(session, phase, 110L, () ->
                beginWalk(
                        session,
                        phase,
                        TutorialSceneBuilder.Station.RANCH,
                        "Maintenant, les animaux. Promis, je ne compte pas les "
                                + "moutons : ça met mon processeur en veille.",
                        () -> ranchChapter(session)
                )
        );
    }

    private void ranchChapter(TutorialSession session) {
        int phase = manager.beginPhase(session);
        manager.chapter(
                session,
                "/eleveur",
                "Un ranch protégé et automatisé",
                0.60D
        );

        manager.later(session, phase, 5L, () ->
                manager.speak(
                        session,
                        "Avec §6/eleveur§f, sélectionne encore deux coins au "
                                + "même Y. Le plugin construit l'enclos, les "
                                + "chemins, les réserves et les zones d'élevage."
                )
        );
        manager.later(session, phase, 65L, () ->
                manager.speak(
                        session,
                        "Les spawners configurés gèrent la population ; le "
                                + "rancher et ses golems s'occupent du domaine. "
                                + "§6/eleveur list§f et §6/eleveur delete <id>§f "
                                + "administrent tes enclos."
                )
        );
        manager.later(session, phase, 135L, () -> {
            manager.showCommand(session, "/eleveur");
            manager.applyScene(
                    session,
                    TutorialSceneBuilder.ranchScene(),
                    success -> {
                        if (!session.isPhase(phase)) {
                            return;
                        }
                        if (!success) {
                            manager.failSession(
                                    session,
                                    "Le ranch de démonstration a échoué."
                            );
                            return;
                        }
                        revealRanch(session, phase);
                    }
            );
        });
    }

    private void revealRanch(TutorialSession session, int phase) {
        World world = session.world();
        manager.magicalEffect(
                session,
                new Location(world, 203.0D, Y + 1.0D, 37.0D)
        );
        spawnVillager(
                session,
                new Location(world, 198.5D, Y, 47.5D),
                "§eÉleveur automatique",
                Villager.Profession.LEATHERWORKER
        );
        spawnAnimal(
                session,
                new Location(world, 193.5D, Y, 35.5D),
                Cow.class,
                "§fMarguerite"
        );
        Sheep sheep = spawnAnimal(
                session,
                new Location(world, 197.5D, Y, 40.5D),
                Sheep.class,
                "§bNuage n°42"
        );
        if (sheep != null) {
            sheep.setColor(DyeColor.LIGHT_BLUE);
        }
        spawnAnimal(
                session,
                new Location(world, 208.5D, Y, 36.5D),
                Pig.class,
                "§dJambon.exe"
        );
        spawnAnimal(
                session,
                new Location(world, 213.5D, Y, 43.5D),
                Chicken.class,
                "§ePoulette quantique"
        );
        spawnGolem(
                session,
                new Location(world, 215.5D, Y, 47.5D),
                "§7Garde du ranch"
        );

        manager.later(session, phase, 25L, () ->
                manager.speak(
                        session,
                        "Voici le troupeau de démonstration. Jambon.exe a "
                                + "demandé une augmentation de carottes ; le "
                                + "service RH étudie le dossier."
                )
        );
        manager.later(session, phase, 105L, () ->
                beginWalk(
                        session,
                        phase,
                        TutorialSceneBuilder.Station.ROYAL,
                        "La prochaine cour est royale. Redresse ta couronne "
                                + "imaginaire et suis-moi.",
                        () -> royalChapter(session)
                )
        );
    }

    private void royalChapter(TutorialSession session) {
        int phase = manager.beginPhase(session);
        manager.chapter(
                session,
                "Protection royale",
                "/garde, /armure et /army",
                0.73D
        );

        manager.later(session, phase, 5L, () ->
                manager.speak(
                        session,
                        "§6/garde§f invoque deux gardes royaux qui te suivent, "
                                + "défendent ta zone et engagent les menaces. "
                                + "Les sous-commandes renvoyer, statut et aide "
                                + "gardent le duo sous contrôle."
                )
        );
        manager.later(session, phase, 75L, () -> {
            manager.showCommand(session, "/garde");
            manager.applyScene(
                    session,
                    TutorialSceneBuilder.royalScene(),
                    success -> {
                        if (!session.isPhase(phase)) {
                            return;
                        }
                        if (!success) {
                            manager.failSession(
                                    session,
                                    "La cour royale n'a pas pu être construite."
                            );
                            return;
                        }
                        revealRoyalGuards(session, phase);
                    }
            );
        });
    }

    private void revealRoyalGuards(
            TutorialSession session,
            int phase) {
        World world = session.world();
        manager.magicalEffect(
                session,
                new Location(world, 234.5D, Y + 2.0D, 38.5D)
        );
        spawnRoyalGuard(
                session,
                new Location(world, 228.0D, Y + 1.0D, 35.5D, 0.0F, 0.0F),
                "§cGarde royal Alpha"
        );
        spawnRoyalGuard(
                session,
                new Location(world, 241.0D, Y + 1.0D, 35.5D, 0.0F, 0.0F),
                "§cGarde royal Bêta"
        );

        manager.later(session, phase, 35L, () ->
                manager.speak(
                        session,
                        "Le duo reste en formation sans te boucher la vue et "
                                + "revient près de toi s'il s'éloigne trop. "
                                + "Oui, eux aussi ont un rayon anti-touriste."
                )
        );
        manager.later(session, phase, 105L, () -> {
            manager.showCommand(session, "/armure");
            ArmorStand mannequin = spawnArmorMannequin(
                    session,
                    new Location(world, 234.5D, Y + 1.0D, 40.5D)
            );
            if (mannequin != null) {
                manager.magicalEffect(
                        session,
                        mannequin.getLocation().add(0.0D, 1.0D, 0.0D)
                );
            }
        });
        manager.later(session, phase, 130L, () ->
                manager.speak(
                        session,
                        "§6/armure§f donne l'Armure du roi GIDON : un set en "
                                + "netherite enchanté qui accorde des effets "
                                + "royaux quand il est porté au complet."
                )
        );
        manager.later(session, phase, 200L, () -> {
            manager.showCommand(session, "/army");
            spawnArmyDemo(session);
        });
        manager.later(session, phase, 225L, () ->
                manager.speak(
                        session,
                        "Et §6/army§f appelle cinq loups et deux golems "
                                + "temporaires. Subtil ? Non. Très rassurant ? "
                                + "Absolument."
                )
        );
        manager.later(session, phase, 300L, () ->
                beginWalk(
                        session,
                        phase,
                        TutorialSceneBuilder.Station.FINAL,
                        "Dernière place : commerce et progression. Mes "
                                + "circuits sentent déjà le discours final.",
                        () -> finalChapter(session)
                )
        );
    }

    private void finalChapter(TutorialSession session) {
        int phase = manager.beginPhase(session);
        manager.chapter(
                session,
                "Commerce et progression",
                "/marchand, /job et /niveau",
                0.86D
        );

        manager.later(session, phase, 5L, () ->
                manager.speak(
                        session,
                        "§6/marchand§f invoque le marchand personnalisé ; "
                                + "§6/marchand open [joueur]§f ouvre son menu "
                                + "pour toi ou une cible, avec la permission."
                )
        );
        manager.later(session, phase, 65L, () -> {
            manager.showCommand(session, "/marchand");
            manager.applyScene(
                    session,
                    TutorialSceneBuilder.finalScene(),
                    success -> {
                        if (!session.isPhase(phase)) {
                            return;
                        }
                        if (!success) {
                            manager.failSession(
                                    session,
                                    "La place finale n'a pas pu être construite."
                            );
                            return;
                        }
                        revealFinalPlace(session, phase);
                    }
            );
        });
    }

    private void revealFinalPlace(
            TutorialSession session,
            int phase) {
        World world = session.world();
        manager.magicalEffect(
                session,
                new Location(world, 266.5D, Y + 2.0D, 21.5D)
        );
        Villager merchant = spawnVillager(
                session,
                new Location(world, 258.5D, Y, 17.5D),
                "§dMarchand MineGus",
                Villager.Profession.WEAPONSMITH
        );
        if (merchant != null) {
            manager.sparkle(
                    session,
                    merchant.getEyeLocation(),
                    Particle.ENCHANT,
                    28
            );
        }

        manager.later(session, phase, 40L, () -> {
            manager.showCommand(session, "/job mineur");
            manager.spawnLabel(
                    session,
                    new Location(world, 265.0D, Y + 3.2D, 28.0D),
                    "§6Métier Mineur §7— §aNiveau 12"
            );
            manager.spawnLabel(
                    session,
                    new Location(world, 265.0D, Y + 2.7D, 28.0D),
                    "§7Vitesse de minage et mineurs supplémentaires"
            );
        });
        manager.later(session, phase, 70L, () ->
                manager.speak(
                        session,
                        "§6/job mineur§f choisit ton métier. Son XP augmente "
                                + "ta vitesse de minage et débloque davantage "
                                + "de mineurs automatiques. §6/job info§f "
                                + "affiche le détail."
                )
        );
        manager.later(session, phase, 145L, () -> {
            manager.showCommand(session, "/niveau");
            manager.spawnLabel(
                    session,
                    new Location(world, 269.0D, Y + 4.0D, 28.0D),
                    "§bNiveau global §7— §a24§7/100"
            );
            manager.spawnLabel(
                    session,
                    new Location(world, 269.0D, Y + 3.5D, 28.0D),
                    "§cPV §8• §bApnée §8• §6Dégâts §8• §eMinage"
            );
        });
        manager.later(session, phase, 175L, () ->
                manager.speak(
                        session,
                        "§6/niveau§f est séparé du métier : l'XP globale "
                                + "améliore progressivement les PV, l'apnée, "
                                + "les dégâts et la vitesse de minage."
                )
        );
        manager.later(session, phase, 245L, () -> {
            manager.showCommand(session, "/minegus");
            Player player = manager.player(session);
            if (player != null) {
                player.sendMessage("§8§m--------------------------------");
                player.sendMessage("§6§l          MineGus - Aide");
                player.sendMessage(
                        "§e/tuto §7• §e/village §7• §e/mineur §7• "
                                + "§e/job §7• §e/niveau"
                );
                player.sendMessage("§8§m--------------------------------");
            }
        });
        manager.later(session, phase, 275L, () ->
                manager.speak(
                        session,
                        "§6/minegus§f affiche l'aide complète : ton pense-bête "
                                + "quand une commande essaie de se cacher."
                )
        );
        manager.later(session, phase, 325L, () -> {
            manager.showCommand(session, "/ping");
            Player player = manager.player(session);
            if (player != null) {
                player.sendMessage("§8[§aServeur§8] §aPong !");
            }
        });
        manager.later(session, phase, 350L, () ->
                manager.speak(
                        session,
                        "§6/ping§f répond Pong. Diagnostic scientifique : le "
                                + "serveur n'est donc pas une patate éteinte."
                )
        );
        manager.later(session, phase, 410L, () ->
                finishTour(session, phase)
        );
    }

    private void finishTour(TutorialSession session, int phase) {
        manager.chapter(
                session,
                "Tutoriel terminé",
                "Tu connais maintenant l'essentiel de MineGus",
                1.0D
        );
        manager.speak(
                session,
                "Visite terminée ! Tu es officiellement diplômé en "
                        + "commandes, villages et suivi de robot magique."
        );

        Player player = manager.player(session);
        if (player != null) {
            player.playSound(
                    player.getLocation(),
                    Sound.UI_TOAST_CHALLENGE_COMPLETE,
                    0.9F,
                    1.05F
            );
            player.getWorld().spawnParticle(
                    Particle.FIREWORK,
                    player.getLocation().add(0.0D, 1.5D, 0.0D),
                    70,
                    2.5D,
                    2.0D,
                    2.5D,
                    0.25D
            );
        }

        int delay = manager.autoExitDelayTicks();
        if (delay <= 0) {
            manager.speak(
                    session,
                    "Prends le temps de regarder la place, puis utilise "
                            + "§6/tuto§f pour rentrer."
            );
            return;
        }

        int delaySeconds = Math.max(1, delay / 20);
        manager.speak(
                session,
                "Je te ramène automatiquement dans §b"
                        + delaySeconds
                        + (delaySeconds == 1 ? " seconde" : " secondes")
                        + "§f. Tu peux aussi utiliser §6/tuto§f tout de suite."
        );
        manager.later(
                session,
                phase,
                delay,
                () -> manager.completeSession(session)
        );
    }

    private void beginWalk(
            TutorialSession session,
            int phase,
            TutorialSceneBuilder.Station destination,
            String line,
            Runnable arrival) {
        if (!session.isPhase(phase)) {
            return;
        }
        manager.speak(session, line);
        manager.walkTo(session, destination, arrival);
    }

    private void spawnVillagePopulation(TutorialSession session) {
        World world = session.world();
        spawnVillager(
                session,
                new Location(world, 20.5D, Y, 5.5D),
                "§eMaire du village",
                Villager.Profession.LIBRARIAN
        );
        spawnVillager(
                session,
                new Location(world, 31.5D, Y, 11.5D),
                "§aAgriculteur",
                Villager.Profession.FARMER
        );
        spawnVillager(
                session,
                new Location(world, 12.5D, Y, 13.5D),
                "§6Artisan",
                Villager.Profession.MASON
        );
        spawnGolem(
                session,
                new Location(world, 27.5D, Y, -3.5D),
                "§7Garde du village"
        );
    }

    private Villager spawnVillager(
            TutorialSession session,
            Location location,
            String name,
            Villager.Profession profession) {
        return manager.spawnDemoEntity(
                session,
                location,
                Villager.class,
                villager -> {
                    villager.setAI(false);
                    villager.setInvulnerable(true);
                    villager.setSilent(true);
                    villager.setCollidable(false);
                    villager.setCustomName(name);
                    villager.setCustomNameVisible(true);
                    villager.setProfession(profession);
                    villager.setVillagerLevel(5);
                }
        );
    }

    private IronGolem spawnGolem(
            TutorialSession session,
            Location location,
            String name) {
        return manager.spawnDemoEntity(
                session,
                location,
                IronGolem.class,
                golem -> {
                    golem.setAI(false);
                    golem.setInvulnerable(true);
                    golem.setSilent(true);
                    golem.setCollidable(false);
                    golem.setPlayerCreated(true);
                    golem.setCustomName(name);
                    golem.setCustomNameVisible(true);
                }
        );
    }

    private <T extends org.bukkit.entity.Animals> T spawnAnimal(
            TutorialSession session,
            Location location,
            Class<T> type,
            String name) {
        return manager.spawnDemoEntity(
                session,
                location,
                type,
                animal -> {
                    animal.setAdult();
                    animal.setAI(false);
                    animal.setInvulnerable(true);
                    animal.setSilent(true);
                    animal.setCollidable(false);
                    animal.setCustomName(name);
                    animal.setCustomNameVisible(true);
                }
        );
    }

    private Husk spawnRoyalGuard(
            TutorialSession session,
            Location location,
            String name) {
        return manager.spawnDemoEntity(
                session,
                location,
                Husk.class,
                guard -> {
                    guard.setAdult();
                    guard.setAI(false);
                    guard.setInvulnerable(true);
                    guard.setSilent(true);
                    guard.setCollidable(false);
                    guard.setCustomName(name);
                    guard.setCustomNameVisible(true);
                    guard.setGlowing(true);
                    EntityEquipment equipment = guard.getEquipment();
                    if (equipment != null) {
                        equipment.setHelmet(
                                new ItemStack(Material.GOLDEN_HELMET)
                        );
                        equipment.setChestplate(
                                new ItemStack(Material.IRON_CHESTPLATE)
                        );
                        equipment.setLeggings(
                                new ItemStack(Material.IRON_LEGGINGS)
                        );
                        equipment.setBoots(
                                new ItemStack(Material.IRON_BOOTS)
                        );
                        equipment.setItemInMainHand(
                                new ItemStack(Material.IRON_SWORD)
                        );
                        equipment.setItemInOffHand(
                                new ItemStack(Material.SHIELD)
                        );
                    }
                }
        );
    }

    private ArmorStand spawnArmorMannequin(
            TutorialSession session,
            Location location) {
        return manager.spawnDemoEntity(
                session,
                location,
                ArmorStand.class,
                stand -> {
                    stand.setArms(true);
                    stand.setBasePlate(false);
                    stand.setGravity(false);
                    stand.setInvulnerable(true);
                    stand.setSilent(true);
                    stand.setCollidable(false);
                    stand.setCustomName("§6Armure du roi GIDON");
                    stand.setCustomNameVisible(true);
                    stand.setGlowing(true);
                    stand.setRightArmPose(new EulerAngle(
                            Math.toRadians(-20.0D),
                            0.0D,
                            Math.toRadians(12.0D)
                    ));

                    EntityEquipment equipment = stand.getEquipment();
                    if (equipment != null) {
                        equipment.setHelmet(
                                new ItemStack(Material.NETHERITE_HELMET)
                        );
                        equipment.setChestplate(
                                new ItemStack(Material.NETHERITE_CHESTPLATE)
                        );
                        equipment.setLeggings(
                                new ItemStack(Material.NETHERITE_LEGGINGS)
                        );
                        equipment.setBoots(
                                new ItemStack(Material.NETHERITE_BOOTS)
                        );
                        equipment.setItemInMainHand(
                                new ItemStack(Material.NETHERITE_SWORD)
                        );
                    }
                }
        );
    }

    private void spawnArmyDemo(TutorialSession session) {
        World world = session.world();
        List<Location> wolfLocations = List.of(
                new Location(world, 226.5D, Y + 1.0D, 41.5D),
                new Location(world, 229.0D, Y + 1.0D, 44.0D),
                new Location(world, 234.5D, Y + 1.0D, 45.0D),
                new Location(world, 240.0D, Y + 1.0D, 44.0D),
                new Location(world, 242.5D, Y + 1.0D, 41.5D)
        );
        int index = 1;
        for (Location location : wolfLocations) {
            int number = index++;
            Wolf wolf = manager.spawnDemoEntity(
                    session,
                    location,
                    Wolf.class,
                    entity -> {
                        entity.setAdult();
                        entity.setAI(false);
                        entity.setInvulnerable(true);
                        entity.setSilent(true);
                        entity.setCollidable(false);
                        entity.setTamed(true);
                        entity.setCollarColor(DyeColor.LIGHT_BLUE);
                        entity.setCustomName(
                                "§bGardien magique " + number
                        );
                        entity.setCustomNameVisible(true);
                    }
            );
            if (wolf != null) {
                manager.sparkle(
                        session,
                        wolf.getLocation().add(0.0D, 0.8D, 0.0D),
                        Particle.END_ROD,
                        9
                );
            }
        }
        spawnGolem(
                session,
                new Location(world, 224.5D, Y + 1.0D, 46.0D),
                "§cGolem de l'armée"
        );
        spawnGolem(
                session,
                new Location(world, 244.5D, Y + 1.0D, 46.0D),
                "§cGolem de l'armée"
        );
        manager.magicalEffect(
                session,
                new Location(world, 234.5D, Y + 2.0D, 43.0D)
        );
    }
}
