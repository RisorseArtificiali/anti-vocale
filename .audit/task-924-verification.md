# TASK-924 verification evidence (trail rows 11/13/15)

Persisted per the cross-review flag: the heaviest claims cited only "gradle outputs in chat". Reproduce any line with the commands below (Mac: JAVA_HOME=~/.sdkman/candidates/java/21.0.10-tem, never -PtestTmpDir).

## Full unit suite (after the review round, commit 627d0cae, 2026-10-30 ~00:30)

Command: `./gradlew :app:testPlayStoreDebugUnitTest --rerun-tasks :app:lintPlayStoreDebug`
Result: `BUILD SUCCESSFUL`, 51/51 tasks executed (no cache hits).
Aggregate from app/build/test-results/testPlayStoreDebugUnitTest/*.xml (294 files):
`tests=2420 failures=0 errors=0 skipped=7`
(2419 before the review round; +1 = the blank-modelType restore test.)

Earlier clean run at the integration point (after cherry-picks, before simplify):
`--rerun-tasks` 42/42 executed, `tests=2419 failures=0 errors=0 skipped=7`.

## Lint

`:app:lintPlayStoreDebug` BUILD SUCCESSFUL both times (post-integration and post-review-round).
HTML report at app/build/reports/lint-results-playStoreDebugUnitTest.html (and the playStoreDebug variant); zero errors, pre-existing warnings only, no hits on the three new strings.

## Scoped runs (fast regression ring, all green both after simplify and after review round)

ModelBundleCodecTest 14/14; ModelViewModelBundleRestoreTest 3/3 then 4/4; ModelViewModelBundleLinkTest 11/11; ExternalModelImporterTest 28/28.

## Interim red states (honest record)

Three compile iterations during the simplify pass (LinkedCatalogRef field name, importer seam interface vs companion, kotlin.test absent from the test classpath) and three test failures during the review round (two hardcoded formatVersion expectations, one fixture-ordering bug in the new test). All resolved in commits d2a039c1 and 627d0cae.
