# MediaForge Android

Application Android minimale qui embarque l’interface web MediaForge dans une
WebView native.

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
- La session reste celle de la WebView ; aucune API native n’est encore
  nécessaire.

Les notifications système natives Android ne sont pas encore implémentées. Les
notifications et toasts rendus par l’interface web restent disponibles.
