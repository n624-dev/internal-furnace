# 設定

[English](CONFIGURATION.md) · [トップ](../README.ja.md)

## 価格・サーバー設定

組み込みの[upgrades.psv](../src/main/resources/data/internal_furnace/upgrades.psv)は全53購入を定義します。変更する場合は`config/internal-furnace-upgrades.psv`へ完全な代替表を置き、サーバーを再起動します。形式:

```text
track|段階|必要Rank|必要M&Sレベル|消費XPレベル|item=count,...
```

未知のアイテム、行の欠落・重複、不正数値は拒否します。1行の素材は最大8種類。性能上限はコードで固定です。自動投入の必要M&Sレベルは**60/65/75/80/90**、消費XPは**35/45/55/70/85**で、別の条件です。

Forgeがworldごとに`serverconfig/internal_furnace-server.toml`を作成します。`inventoryScanTicks`の既定値は10、`idleLossMilliheat`は1000（断熱前1 heat/tick）。1 heatは1000 milliheatです。基準放熱と多くの価格は初期バランス値であり、すべてのサーバー経済に適合する保証ではありません。

## 自動投入ルール

通常は視覚的エディターを使います。詳細設定用のJSON欄も利用できます。

```json
{"enabled":true,"allowNamed":false,"allowEnchanted":false,"rules":[{"action":"INPUT","any":[[{"field":"RARITY","value":"common"},{"field":"LEVEL_LT","value":"40"}]]}]}
```

これは解析できるcommonのM&S装備で、装備レベル40未満を対象にする例です。自動投入IVかVが必要です。レアリティは表示名を推測せず、手持ちから取得した実IDを使ってください。

`any`の外側はOR、各グループの内側はANDです。条件はITEM、CATEGORY、TAG、DURABILITY_LT、ENCHANTED、NAMED、RARITY、LEVEL_LT、KIND。購入段階に応じてサーバーでも検証します。

- カテゴリ: `iron_gear`、`gold_gear`、`ore`、`food`。
- 種類: `armor`、`weapon`、`tool`、`other`。
- 数値上限は「未満」です。
- 最大16ルール・合計64条件、1ルール8 ORグループ、1グループ8条件、JSON4096文字。
- PROTECTが常に優先。スクリプトや正規表現は実行しません。
- 新規ルールは保護。自動投入は明示的にONにするまで無効です。

燃料設定では解放段階に応じて優先順や熱しきい値を指定できます。需要予測でも燃料は1個単位で消費するため、余熱が生じる場合があります。
