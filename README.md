# MediaForge Android

Application Android minimale qui embarque l’interface web MediaForge dans une
WebView native.

Au premier démarrage, un écran de configuration demande l’URL du serveur, le
nom d’utilisateur et le mot de passe. Le mot de passe est chiffré avec Android
Keystore et la connexion web est ensuite remplie automatiquement.

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
- Un bouton `Configuration` reste disponible dans la barre native pour modifier
  le serveur ou les identifiants.

Les notifications système natives Android ne sont pas encore implémentées. Les
notifications et toasts rendus par l’interface web restent disponibles.

## Builds et releases GitHub

Le workflow `.github/workflows/android-release.yml` se déclenche à chaque push
sur `main`. Il calcule automatiquement le prochain tag patch (`v0.1.1`, puis
`v0.1.2`, etc.), compile un APK debug installable, pousse le tag et crée une
Release GitHub avec l’APK en pièce jointe.

Pour définir l’URL utilisée par les releases, créer une variable de dépôt
GitHub nommée `MEDIAFORGE_URL`. Il est aussi possible de lancer le workflow
manuellement et de fournir l’URL dans son champ d’entrée.

Il n’est donc plus nécessaire de créer les tags manuellement. Une exécution
manuelle du workflow reste possible depuis l’onglet Actions pour tester un
build avec une URL différente.

L’APK est actuellement signé avec la clé debug générée par Android. Une clé de
signature de production devra être ajoutée dans les secrets GitHub avant une
publication sur Google Play.
