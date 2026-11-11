# Suivi GitHub — widget Android

Application Android indépendante affichant l'état des workflows GitHub de trois dépôts : `BOULANGERIES-FRANCE`, `STATIONS-TOTAL-FRANCE` et `iriscope`.

## Sécurité
- Lecture seule : l'application consulte l'API GitHub publique et ne modifie aucun dépôt surveillé.
- Le code de cette application est hébergé uniquement dans ce dépôt indépendant.
- Aucun jeton GitHub n'est requis pour les dépôts publics.

## Fonctionnement
Le widget conserve le dernier état connu et propose une actualisation manuelle. Une surveillance en arrière-plan peut être limitée par les règles d'économie d'énergie et les restrictions de services Android. La fréquence de deux minutes est une cible, pas une garantie système. Après l'arrêt de la surveillance lorsque tous les workflows sont terminés, une actualisation manuelle est nécessaire pour détecter un nouveau lancement.

## Compilation
Le workflow GitHub Actions `Build APK` compile l'application et publie l'APK debug comme artefact téléchargeable. Le dépôt est en cours de mise en place : l'APK ne doit être considéré comme disponible qu'après une compilation réussie.