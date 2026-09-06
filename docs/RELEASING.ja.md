# Releaseと保守

[English](RELEASING.md) · [トップ](../README.ja.md)

このリポジトリをInternal Furnaceのソース・Issue・ビルド・GitHub Releaseの管理元とします。運用プロジェクトは独自コピーを編集せず、バージョン指定のReleaseをSHA-256検証して取り込みます。Minecraftサーバーへ配置・再起動するworkflowはありません。

## Releaseの準備

1. ブランチでコードと日英文書を変更し、PRを作成します。
2. `gradle.properties`の`mod_version`、両言語の変更履歴、日英の`docs/releases/<version>.md`を更新します。
3. CIの成功を確認します。メタデータ/文書検査、固定コンテナでの2回のビルド・テスト、JAR検査、ハッシュ一致確認を行います。実行時動作に関わる変更は、使い捨て専用サーバー・実クライアントでも検証します。
4. レビュー済み変更を`main`へマージし、一致するタグを明示的に作成・pushします。例:

```bash
git tag -a v0.1.0-beta.1 -m 'Internal Furnace 0.1.0-beta.1'
git push origin v0.1.0-beta.1
```

次回以降は新しいバージョン/タグを使います。公開タグの移動や公開JARの差し替えはしません。

## Actionsの動作

PR、mainへのpush、手動実行はビルド・テストまでです。`v*`タグではビルド成功後にReleaseジョブも動きます。タグは`mod_version`との完全一致とリリースノートを要求します。Releaseジョブだけ`contents: write`を持ち、GitHub組み込みトークンを使用するため個人トークンのSecretは不要です。

Releaseはreobfuscation済みの両側共通JARと`SHA256SUMS`を含みます。`0.1.0-beta.1`等のハイフンを含む版はGitHub Pre-releaseになります。テスト用JARは公開しません。ビルド後に公開が失敗した場合は原因を確認して失敗ジョブを再実行し、公開済みReleaseを上書きしないでください。

取得したファイルは同じフォルダーで`sha256sum --check SHA256SUMS`により検証できます。接続時にも両側JARの一致を確認します。

## CurseForge

CurseForgeの登録・公開は管理者が別途行います。このリポジトリにCurseForgeトークン・アップロード処理・Project IDはなく、掲載済みとは案内していません。登録後、公開リンクと、GitHubの検証済み成果物を使うアップロード処理を別の変更として追加できます。API認証情報はソースやリリースノートではなくSecretsへ保存します。
