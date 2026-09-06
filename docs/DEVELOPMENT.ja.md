# 開発

[English](DEVELOPMENT.md) · [トップ](../README.ja.md)

## ビルド

Releaseはdigest固定のDockerイメージ内でGradle 8.1.1 / Java17を使います。Linux、Docker、Bash、curl、Python 3.11以上、coreutilsを用意し、次を実行します。

```bash
bash build.sh
```

公式CurseForgeからM&S 6.4.7・LoE 2.1.11の正確なコンパイル用JARだけを取得し、SHA-256を検証してGit対象外の`.deps/`へ保存します。取得できない場合は正確なJARをそこへ置いてください。不一致は拒否します。第三者JARをコミット・Release同梱することはありません。

コンテナへ渡すのはこの作業ディレクトリだけで、2CPU・3GiBに制限します。Docker socket、認証情報、Minecraft worldは渡しません。Gradle/Forge依存関係の取得にはネット接続が必要です。開発用マシンで実行してください。

成果物は`build/libs/internal-furnace-<mod_version>.jar`です。バージョンは[gradle.properties](../gradle.properties)で指定します。reobfuscation済みJARにはMIT表記とLICENSEを含め、テストドライバーや依存MODは同梱しません。

ネイティブビルドはJava17とPython 3.11以上を用意して実行します。

```bash
bash scripts/fetch-dependencies.sh
./gradlew --no-daemon clean build
bash verify.sh
```

Windowsは検証済み依存JARを`.deps/`へ置いて`gradlew.bat`を使えます。Wrapperの配布ZIPとJARはSHA-256で固定します。Java17でもコンパイラーの更新によりバイトコードが変わるため、ネイティブ成果物と固定コンテナのハッシュが異なる場合があります。両側には同一Release JARを入れてください。

## テスト

```bash
bash test-core.sh
python3 scripts/check-project.py
./gradlew check
```

`test-core.sh`は実際のコア・編集・分配クラスをJava17互換、`-Xlint:all -Werror`でコンパイルし、決定的テストとシード付きテストを実行します。Gradleの`check`はCoreTests・EditorTestsと実機ドライバーのコンパイルを含みます。`verify.sh`はJAR構造検査であり、実接続テストではありません。

## 実Minecraftでの検証

```bash
./gradlew runtimeTestJar
```

別JARの`*-runtime-tests.jar`を作成します。**使い捨てworldと使い捨てプレイヤー専用**です。所持品・XP・M&Sレベル・かまどデータを上書きするため、配布MODに含めないでください。ドライバーを入れたサーバー・クライアントには`-Dinternalfurnace.disposableTest=true`が必要です。

専用サーバーと実グラフィカルクライアントへForgeと固定依存MODを導入します。専用サーバーの検証を統合サーバーで代替しないでください。コマンドは権限2が必要です。

- `/furnacetest suite`: 実購入・メニュー・レシピ・自動化の検証。
- `/furnacetest readonly`: 別プレイヤー`FurnaceObserver`を接続し、管理閲覧画面での操作拒否を検証。
- `/furnacetest prepare`: 39個のアイテムを持つ停止状態を作成。1tick以上待ち、`/furnacetest snapshot`でテストサーバーのディレクトリへ完全NBTを保存。
- `/furnacetest verify`: 再接続・正常再起動・Dimension移動・keepInventory=trueでの死亡/復活後に完全一致を確認。復活完了を待って実行します。
- `/furnacetest deathoff`: keepInventory=falseで死亡後、39個のドロップ・内部空・熱0・強化保持を確認。古いテスト用ドロップを除き、復活地点から離れて死亡させ、回収前に実行します。

クライアント補助はゲームディレクトリの`furnace-client-command.txt`を一度読んで削除します。`language en_us`、`language ja_jp`、`scale 1`〜`scale 4`で画面を揃えられます。言語の再読み込みが終わってから画面を確認してください。

正確なJARハッシュ、環境、期待値と結果、実入力かサーバー直接検証かを記録してください。認証情報、world、非公開の配布構成は公開レポートへ含めません。
