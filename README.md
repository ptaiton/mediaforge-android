# MediaForge Android

Application Android minimale qui embarque l’interface web MediaForge dans une
WebView native.

Au premier démarrage, un écran de configuration demande l’URL du serveur, le
nom d’utilisateur et le mot de passe. Le mot de passe est chiffré avec Android
Keystore ; l’application authentifie directement la session puis ouvre la
WebView avec son cookie de connexion.

## Ouvrir le projet

Ouvrir le dossier `mediaforge-android` dans Android Studio, puis synchroniser
le projet Gradle. Le SDK Android 35 et un JDK 17 sont nécessaires.

## URL du serveur

Par défaut, l’application vise `http://10.0.2.2:8080/`, qui correspond au port
8080 de la machine hôte depuis l’émulateur Android. Pour viser une instance
distante ou un téléphone sur le réseau local, passer une URL au build :

```bash
gradle assembleDebug -PMEDIAFORGE_URL=https://mediaforge.example.com/
```

L’URL doit être accessible depuis le téléphone. HTTPS est recommandé pour toute
instance exposée hors du réseau local. Le support HTTP en clair est conservé
pour le développement local avec l’émulateur.

## Fonctionnement de la première version

- JavaScript, stockage local, cookies et WebSockets sont activés pour conserver
  le fonctionnement de l’app web et de la session MediaForge.
- Le bouton retour Android navigue dans l’historique de la WebView.
- Les schémas externes (`mailto:`, liens d’applications, etc.) sont ouverts par
  Android.
- En mode Android, le menu mobile de MediaForge affiche `App compagnon` avec
  une icône de réglages pour rouvrir cet écran de configuration.
- La WebView occupe tout l’écran utile. Les marges système Android sont
  appliquées automatiquement en haut et en bas pour éviter l’encoche et la
  barre de navigation.

Les notifications système Android utilisent Firebase Cloud Messaging. Le
workflow accepte le secret GitHub `FIREBASE_GOOGLE_SERVICES_JSON` contenant le
fichier `google-services.json`. Si ce secret est absent, l’APK reste compilable
mais les notifications push sont désactivées.

## Builds et releases GitHub

Le workflow `.github/workflows/android-release.yml` se déclenche à chaque push
sur `main`. Il calcule automatiquement le prochain tag patch (`v0.1.1`, puis
`v0.1.2`, etc.), compile un APK release signé, pousse le tag et crée une Release
GitHub avec l’APK en pièce jointe.

Pour définir l’URL utilisée par les releases, créer une variable de dépôt
GitHub nommée `MEDIAFORGE_URL`. Il est aussi possible de lancer le workflow
manuellement et de fournir l’URL dans son champ d’entrée.

Il n’est donc plus nécessaire de créer les tags manuellement. Une exécution
manuelle du workflow reste possible depuis l’onglet Actions pour tester un
build avec une URL différente.

La clé de signature persistante est conservée dans les secrets GitHub et dans
le dossier local ignoré `signing/`. Ce fichier doit être sauvegardé : perdre la
clé empêcherait les futures mises à jour de l’application.

Le backend doit aussi être configuré avec le JSON du compte de service Firebase
dans `config/firebase-service-account.json`. L’application Android enregistre
automatiquement son jeton après la connexion ; le serveur l’utilise ensuite
pour envoyer les notifications de fin de téléchargement.
