# Manuel utilisateur Anti-Vocale

Anti-Vocale transcrit les messages vocaux sur votre appareil Android, entièrement hors ligne. L'audio ne quitte jamais votre téléphone : la transcription s'exécute localement avec des modèles d'IA ouverts, sans compte, sans service cloud, sans télémétrie.

Manuel mis à jour pour la version 1.15.

## Sommaire

1. [Premiers pas](#premiers-pas)
2. [Choisir un modèle](#choisir-un-modèle)
3. [Transcription au quotidien](#transcription-au-quotidien)
4. [Messages vocaux WhatsApp, automatiquement](#messages-vocaux-whatsapp-automatiquement)
5. [Résultats : étiquettes, formats et la fenêtre flottante](#résultats--étiquettes-formats-et-la-fenêtre-flottante)
6. [Audio long, file d'attente et nouvelles tentatives](#audio-long-file-dattente-et-nouvelles-tentatives)
7. [Modèles de la communauté et import](#modèles-de-la-communauté-et-import)
8. [Tasker et automatisation](#tasker-et-automatisation)
9. [Paramètres par application](#paramètres-par-application)
10. [Confidentialité](#confidentialité)
11. [Dépannage](#dépannage)
12. [FAQ](#faq)

Les sections 8a, 8b et 8c couvrent les surfaces d'automatisation : la surveillance planifiée des dossiers, le déport distant et le fournisseur Open Transcribe.

## Premiers pas

1. Installez Anti-Vocale depuis votre magasin d'applications (Google Play ou F-Droid) ou à partir d'un APK publié sur GitHub.
2. Ouvrez l'application une fois. Sur l'onglet **Modèles**, vous verrez les modèles intégrés disponibles au téléchargement.
3. Téléchargez un modèle. Pour la plupart des gens, le premier choix recommandé est **Parakeet TDT (stock int8, 640 Mo)** : rapide, compact, il couvre 25 langues européennes.
4. Pour transcrire, partagez un message vocal depuis n'importe quelle messagerie (WhatsApp, Telegram, Signal et d'autres) vers Anti-Vocale. Une notification apparaît pendant le traitement, puis une deuxième notification avec le texte.
5. Touchez la notification de résultat pour copier le texte, le partager ou le renvoyer dans la conversation d'où il provient.

Aucune configuration supplémentaire n'est nécessaire. Tout ce qui suit est facultatif.

## Choisir un modèle

Les modèles diffèrent par la taille, la vitesse, la couverture des langues et la précision. L'onglet Modèles affiche l'essentiel sur chaque carte avant le téléchargement. Repères rapides :

| Modèle | Taille | Langues | Remarques |
|---|---|---|---|
| Parakeet TDT stock int8 | 640 Mo | 25 européennes | Rapide et léger ; la recommandation par défaut |
| Parakeet TDT SmoothQuant | 862 Mo | 25 européennes | Plus précis, plus lourd ; demande plus de RAM |
| Whisper Turbo | 988 Mo | 101 | Meilleur équilibre de la famille Whisper |
| Whisper Medium | 903 Mo | 101 | Plus lent que Turbo, pas meilleur pour la plupart des audios |
| Whisper Small | 358 Mo | 101 | Whisper le plus léger ; qualité correcte |
| Whisper Distil Italian | 938 Mo | Italien uniquement | Meilleure précision en italien des modèles intégrés |
| Qwen3-ASR | 938 Mo | Multilingue | Architecture alternative |
| Nemotron streaming | 640 Mo | Multilingue | Affiche le texte pendant que vous parlez (streaming) |
| GigaAM v3 | 326 Mo | Russe | Spécialiste du russe |

Quelques repères :
- Si vous transcrivez surtout une seule langue, un modèle spécialisé (Distil Italian, GigaAM) surpasse un généraliste de taille égale.
- Si votre téléphone a 4 Go de RAM ou moins, préférez les modèles de moins de 500 Mo.
- Les modèles Gemma (listés à part sur l'onglet Modèles) sont des modèles de langage plus grands, capables aussi de transcrire. Ils sont intéressants pour expérimenter, mais plus lourds et plus lents que les modèles ASR dédiés.

## Transcription au quotidien

- Partagez un message vocal vers Anti-Vocale. Le traitement commence immédiatement, même écran éteint.
- La notification de résultat propose : **Copier**, **Partager** et, quand l'application source est prise en charge, **Envoyer à [App]**, qui colle le texte directement dans la conversation d'où venait le message vocal.
- Avec la Copie automatique activée (Paramètres), le texte est déjà dans le presse-papiers quand la notification arrive ; la notification vous l'indique.
- Chaque transcription est conservée dans l'onglet **Historique**, avec le modèle utilisé, la durée et le temps de traitement. Un appui long sur une entrée permet de réessayer, de copier, de supprimer ou de signaler un mauvais résultat par e-mail ; **Re-transcrire** relance le même audio avec un autre modèle de votre choix.
- Avec un modèle **Whisper ou SenseVoice**, chaque transcription dépliée porte une puce de langue : le drapeau de la langue détectée par le modèle, avec un appui pour vérifier ou épingler quand l'estimation est fausse (les petits modèles se trompent parfois). Les autres familles de modèles ne rapportent pas de langue détectée, la puce reste donc absente pour elles ; le réglage dans les Paramètres le dit et reste désactivé.
- Avec l'Enregistrement automatique dans un dossier (Paramètres), chaque transcription est aussi écrite dans un fichier .txt, dans le dossier de votre choix.

## Messages vocaux WhatsApp, automatiquement

Anti-Vocale peut transcrire les nouveaux messages vocaux WhatsApp dès leur arrivée, sans le moindre geste. Le texte arrive dans l'onglet Historique et sous forme de notification de résultat ; si Android bloque un démarrage en arrière-plan (écran éteint, par exemple), une notification en un appui lance la transcription à la place.

La configuration prend une minute, dans **Paramètres > Messages vocaux** :

1. Activez la **Transcription des messages vocaux** et accordez l'accès audio quand Android le demande. Si vous avez refusé deux fois, Android n'affiche plus de boîte de dialogue ; l'indication rouge qui apparaît à la place ouvre la page système où l'autorisation peut encore être accordée.
2. Choisissez une fois le dossier WhatsApp Voice Notes via le sélecteur guidé. Un dossier erroné est refusé immédiatement ; vous pouvez le changer ou le retirer à tout moment depuis la même carte.
3. Facultatif : activez aussi la **reconnaissance de l'expéditeur** et accordez l'accès aux notifications. La ligne d'Historique porte alors le nom de l'expéditeur.

Deux règles honnêtes régissent les étiquettes. Un nom n'apparaît que lorsque la notification du message lui-même le confirme, c'est-à-dire quand elle est arrivée pendant que la fonction était activée et que sa durée annoncée correspond à l'audio : les noms ne sont jamais devinés. Et les messages que vous téléchargez manuellement (téléchargement automatique désactivé) restent sans étiquette, car leur notification est trop ancienne pour être fiable. La liste d'attente des notifications récentes vit uniquement en mémoire et disparaît quand la fonction est désactivée.

La confidentialité répond au même contrat que partout ailleurs : pendant que la détection est active, l'application ne lit que la liste du dossier de messages vocaux WhatsApp ; l'audio d'un message n'est lu que pour le transcrire, entièrement sur cet appareil ; fonction désactivée, rien n'est lu du tout.

Une réserve, dite sans détour : sur les téléphones aux économiseurs de batterie agressifs, les messages qui arrivent pendant que le système a suspendu l'application peuvent être manqués jusqu'à son réveil. Si vous comptez sur le mode automatique, exclure Anti-Vocale de l'optimisation de la batterie le rend fiable. WhatsApp seulement pour l'instant ; Telegram est au programme.

## Résultats : étiquettes, formats et la fenêtre flottante

- **Étiquettes locuteur** : à activer dans les Paramètres ; les transcriptions de conversations reçoivent une attribution de locuteur phrase par phrase, rendue sous forme de tours SPEAKER dans le résultat et dans chaque export.
- **Partager au-delà du texte** : depuis une ligne de résultat, partagez en texte brut, en texte horodaté, en SRT ou en VTT. Les formats horodatés exigent une transcription qui porte des cues. « Partager en... » s'active dans les Paramètres.
- **Picture-in-Picture** : pendant qu'une transcription tourne, une fenêtre flottante peut afficher le texte au fur et à mesure, pour continuer à utiliser d'autres applications.
- **Re-transcrire** : relancez n'importe quelle entrée avec un autre modèle directement depuis sa ligne, et comparez.

## Audio long, file d'attente et nouvelles tentatives

- Toute durée d'audio fonctionne avec n'importe quel modèle : les enregistrements longs sont découpés et recollés automatiquement. (Les anciennes versions avaient une limite de 6:40 avec Parakeet ; elle a disparu.)
- Partagez plusieurs messages d'affilée : ils se mettent en file d'attente. Chaque élément en attente peut être annulé individuellement depuis sa notification pendant qu'une autre transcription tourne.
- Une transcription échouée peut être relancée d'un simple appui depuis l'onglet Historique.

## Modèles de la communauté et import

Le catalogue intégré ne couvre pas toutes les langues. Anti-Vocale embarque un catalogue communautaire de modèles supplémentaires que vous importez en deux gestes : onglet Modèles, Avancé, ONNX Sherpa, Importer du catalogue, filtrez par votre langue, touchez le modèle, confirmez. Le catalogue grandit sans cesse et compte aujourd'hui plus de 30 entrées validées : l'arabe (dialectal, complet et léger), l'allemand (streaming et un fine-tune Whisper), l'espagnol (streaming et Moonshine léger), le suisse allemand, l'hébreu, le persan, le russe, le vietnamien, le coréen, l'ukrainien, six langues indiennes (bengali, gujarati, hindi, marathi, tamoul, télougou), Canary Flash pour l'anglais et le français, SenseVoice, Dolphin, Orukeet (un fine-tune de Parakeet couvrant 24 langues dont l'italien) et un Whisper Tiny multilingue à faible RAM. Si votre langue manque, ouvrez un ticket : c'est ainsi que les entrées sont ajoutées.

Les utilisateurs avancés peuvent aussi :
- importer un modèle depuis l'URL d'un dépôt Hugging Face ou depuis un lien d'entrée de catalogue (la partie avancée de la même boîte de dialogue) ;
- importer un ensemble de fichiers de modèle depuis un dossier du téléphone ;
- exporter un modèle téléchargé sous forme de **bundle hors ligne** et l'importer sur un autre appareil : un modèle téléchargé une fois se déplace sans être retéléchargé ;
- pointer l'application vers un autre index de catalogue (l'action « modifier » à côté de la source du catalogue), maintenu par n'importe qui, par exemple votre communauté.

Le format d'import et les fichiers exigés sont documentés dans [modèles externes](../../external-models.md).

## Tasker et automatisation

Anti-Vocale accepte un broadcast que Tasker (ou n'importe quelle application d'automatisation) peut envoyer pour transcrire un fichier sans toucher à l'interface :

```
Action: com.antivocale.app.PROCESS_REQUEST
Extras: request_type=audio, file_path=/path/to/audio, task_id=your-id
Optional: backend_id=<model id> to pick the model for that request
```

Le résultat revient sous forme de broadcast de réponse. Le guide complet avec exemples se trouve dans le [guide Tasker](../../TASKER_GUIDE.md).

## Surveillance planifiée des dossiers

Anti-Vocale peut surveiller les dossiers de votre choix et transcrire automatiquement les nouveaux fichiers audio : Paramètres, Surveillance planifiée des dossiers, Surveiller un dossier. Les analyses s'exécutent à la fréquence que le système accorde ; les économiseurs de batterie agressifs peuvent les retarder, et le bouton Analyser maintenant fonctionne toujours. Chaque nouveau fichier audio est transcrit avec le modèle actuellement sélectionné et aboutit dans l'onglet Historique comme toute autre exécution. À partir d'Android 11, le sélecteur de dossiers peut ouvrir des emplacements du stockage partagé, comme le dossier média d'une messagerie ; la transcription automatique des messages vocaux d'une conversation devient possible sans les partager un par un.

## Déport distant (expérimental)

Si vous exploitez votre propre serveur de transcription, Anti-Vocale peut lui confier les transcriptions longues ou lourdes : Paramètres, Déport distant (expérimental). Tout serveur compatible OpenAI parlant l'API /v1/audio/transcriptions fonctionne (OmniVoice, le serveur whisper.cpp, et d'autres). L'encart dans l'application le dit clairement : quand la fonction est activée, l'audio des transcriptions confiées n'est envoyé qu'à l'adresse du serveur que vous saisissez, où que ce serveur se trouve (votre réseau local ou n'importe où sur Internet) ; tout le reste est transcrit sur l'appareil, et la fonction est désactivée par défaut.

## Open Transcribe (expérimental)

Les applications qui parlent le contrat ouvert Open Transcribe, comme Forkgram (un client Telegram), peuvent utiliser Anti-Vocale comme transcripteur intégré de leurs messages vocaux : activez **Paramètres > Automatisation > Open Transcribe API** (désactivé par défaut), puis choisissez Anti-Vocale dans les réglages de transcription hors ligne du client. Les messages vocaux sont transcrits avec les modèles propres de l'application et l'audio ne quitte jamais l'appareil. Désactiver l'interrupteur retire de nouveau l'application des sélecteurs des clients.

## Paramètres par application

Pour chaque application depuis laquelle vous partagez (WhatsApp, Telegram, ...), vous pouvez configurer séparément : l'affichage de l'action « Renvoi rapide » (renvoyer le texte dans le chat), la copie automatique et le son de notification. Onglet Paramètres, Paramètres par application.

## Confidentialité

- La transcription est 100 % sur l'appareil. Aucun audio, aucun texte, aucune métadonnée ne quitte jamais votre téléphone.
- L'application n'a pas de permission Internet pour la transcription ; le réseau ne sert que quand vous téléchargez explicitement un modèle.
- L'Historique reste sur votre appareil et vous appartient : effacez-le à tout moment depuis l'onglet Historique.
- La version Play inclut le rapport de plantages Crashlytics (visible et désactivable dans les paramètres Android) ; la version F-Droid n'en a pas.

## Dépannage

**La transcription ne se termine jamais / la notification disparaît.**
Certaines marques de téléphones (Vivo, OPPO, certains Xiaomi et Samsung) suspendent agressivement les applications en arrière-plan. Ouvrez Anti-Vocale une fois et, si l'application le propose, accordez l'autorisation de fonctionnement en arrière-plan ; ou trouvez l'application dans les paramètres de batterie et réglez-la sur « Sans restriction ». L'application détecte cette situation et l'explique dans une notification quand elle se produit.

**« Mémoire insuffisante » ou plantages avec les gros modèles.**
Les modèles indiquent leur taille sur la carte. Sur les téléphones avec 4 Go de RAM ou moins, utilisez des modèles de moins de 500 Mo. Si une transcription échoue avec un message de mémoire insuffisante, essayez un fichier plus court, un modèle plus petit, ou fermez d'autres applications.

**La qualité de transcription est mauvaise.**
Essayez un modèle spécialisé pour votre langue (voir le tableau plus haut). Appui long sur la mauvaise entrée dans l'Historique, puis utilisez Signaler pour nous envoyer les détails (modèle, durée, temps de traitement ; l'extrait de transcription seulement si vous choisissez de l'inclure).

**NNAPI fait planter l'application.**
Si vous avez activé le fournisseur NNAPI dans les Paramètres et que l'application plante maintenant, elle revient automatiquement au CPU au prochain démarrage. NNAPI dépend fortement du chipset du téléphone ; si les plantages se répètent, laissez-le sur CPU.

**Les messages vocaux automatiques n'arrivent plus.**
Deux causes habituelles. Si le dossier de WhatsApp a été supprimé puis recréé (une réinstallation, un outil de nettoyage), l'autorisation de dossier enregistrée peut mourir : ouvrez Paramètres > Messages vocaux et choisissez à nouveau le dossier depuis la carte. Si votre téléphone suspend agressivement les applications en arrière-plan, les messages qui arrivent pendant que l'application est suspendue sont manqués jusqu'à son réveil : l'exemption de batterie décrite plus haut est le remède, et elle compte davantage pour cette fonction que pour le partage manuel.

## FAQ

**Cela fonctionne-t-il sans internet ?**
Oui. Après le téléchargement d'un modèle, la transcription fonctionne entièrement hors ligne.

**Quelles messageries sont prises en charge ?**
N'importe quelle application capable de partager un fichier audio. L'action de renvoi vise actuellement un sous-ensemble d'applications (WhatsApp, Telegram et d'autres, détectées automatiquement).

**Où sont mes transcriptions ?**
Dans l'onglet Historique et, en option, dans des fichiers .txt dans un dossier de votre choix. Rien n'est stocké ailleurs.

**Peut-il transcrire les messages vocaux automatiquement dès leur arrivée ?**
Oui, pour WhatsApp : activez Paramètres > Messages vocaux, accordez l'accès audio et choisissez une fois le dossier WhatsApp Voice Notes. Les nouveaux messages sont transcrits dès leur arrivée, et la ligne peut porter le nom de l'expéditeur. La surveillance planifiée des dossiers reste la voie générique pour le dossier média de n'importe quelle messagerie (Android 11+), et Telegram est au programme. Sur les téléphones aux économiseurs de batterie agressifs, les messages qui arrivent pendant que l'application est suspendue peuvent être manqués : l'exemption de batterie rend le mode automatique fiable.

**Pourquoi un message vocal n'a-t-il pas de nom d'expéditeur ?**
Le nom n'apparaît que si la notification du message est arrivée pendant que la reconnaissance de l'expéditeur était activée et que sa durée annoncée correspond à l'audio. Les messages téléchargés manuellement (téléchargement automatique désactivé) et ceux dont la notification n'est jamais arrivée n'ont pas de nom, exprès : un mauvais nom est pire que pas de nom du tout.

**Pourquoi deux magasins (Play et F-Droid) ?**
La même application, les mêmes fonctions. F-Droid la compile depuis les sources sans composant propriétaire ; la version Play ajoute le rapport de plantages automatique.

**Est-ce vraiment privé ?**
Oui. Le code source est ouvert ; vous pouvez vérifier qu'aucune donnée ne quitte l'appareil. Voir la politique de confidentialité du dépôt.

---

Une erreur ou un manque ? Ouvrez un ticket sur [GitHub](https://github.com/RisorseArtificiali/anti-vocale/issues).
