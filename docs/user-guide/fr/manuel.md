# Manuel utilisateur Anti-Vocale

Anti-Vocale transcrit les messages vocaux sur votre appareil Android, entièrement hors ligne. L'audio ne quitte jamais votre téléphone : la transcription s'exécute localement avec des modèles d'IA ouverts, sans compte, sans service cloud, sans télémétrie.

Manuel mis à jour pour la version 1.11.

## Sommaire

1. [Premiers pas](#premiers-pas)
2. [Choisir un modèle](#choisir-un-modèle)
3. [Transcription au quotidien](#transcription-au-quotidien)
4. [Audio long, file d'attente et nouvelles tentatives](#audio-long-file-dattente-et-nouvelles-tentatives)
5. [Modèles de la communauté et import](#modèles-de-la-communauté-et-import)
6. [Tasker et automatisation](#tasker-et-automatisation)
7. [Paramètres par application](#paramètres-par-application)
8. [Confidentialité](#confidentialité)
9. [Dépannage](#dépannage)
10. [FAQ](#faq)

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
- Chaque transcription est conservée dans l'onglet **Historique**, avec le modèle utilisé, la durée et le temps de traitement. Un appui long sur une entrée permet de réessayer, de copier, de supprimer ou de signaler un mauvais résultat par e-mail.
- Avec l'Enregistrement automatique dans un dossier (Paramètres), chaque transcription est aussi écrite dans un fichier .txt, dans le dossier de votre choix.

## Audio long, file d'attente et nouvelles tentatives

- Toute durée d'audio fonctionne avec n'importe quel modèle : les enregistrements longs sont découpés et recollés automatiquement. (Les anciennes versions avaient une limite de 6:40 avec Parakeet ; elle a disparu.)
- Partagez plusieurs messages d'affilée : ils se mettent en file d'attente. Chaque élément en attente peut être annulé individuellement depuis sa notification pendant qu'une autre transcription tourne.
- Une transcription échouée peut être relancée d'un simple appui depuis l'onglet Historique.

## Modèles de la communauté et import

Le catalogue intégré ne couvre pas toutes les langues. Anti-Vocale embarque un catalogue communautaire de modèles supplémentaires que vous importez en deux gestes : onglet Modèles, Avancé, ONNX Sherpa, Importer du catalogue, filtrez par votre langue, touchez le modèle, confirmez. Les modèles communautaires couvrent actuellement l'arabe (dialectal), le russe, l'espagnol, l'allemand (streaming) et le suisse allemand.

Les utilisateurs avancés peuvent aussi :
- importer un modèle depuis l'URL d'un dépôt Hugging Face ou depuis un lien d'entrée de catalogue (la partie avancée de la même boîte de dialogue) ;
- importer un ensemble de fichiers de modèle depuis un dossier du téléphone ;
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

## FAQ

**Cela fonctionne-t-il sans internet ?**
Oui. Après le téléchargement d'un modèle, la transcription fonctionne entièrement hors ligne.

**Quelles messageries sont prises en charge ?**
N'importe quelle application capable de partager un fichier audio. L'action de renvoi vise actuellement un sous-ensemble d'applications (WhatsApp, Telegram et d'autres, détectées automatiquement).

**Où sont mes transcriptions ?**
Dans l'onglet Historique et, en option, dans des fichiers .txt dans un dossier de votre choix. Rien n'est stocké ailleurs.

**Peut-il transcrire les messages vocaux automatiquement dès leur arrivée ?**
Pas encore. C'est au programme ; aujourd'hui, le partage ne demande qu'un appui.

**Pourquoi deux magasins (Play et F-Droid) ?**
La même application, les mêmes fonctions. F-Droid la compile depuis les sources sans composant propriétaire ; la version Play ajoute le rapport de plantages automatique.

**Est-ce vraiment privé ?**
Oui. Le code source est ouvert ; vous pouvez vérifier qu'aucune donnée ne quitte l'appareil. Voir la politique de confidentialité du dépôt.

---

Une erreur ou un manque ? Ouvrez un ticket sur [GitHub](https://github.com/RisorseArtificiali/anti-vocale/issues).
