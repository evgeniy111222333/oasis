# Деплой (дублікат-каталог)

Повна інструкція — в авторитетному каталозі: **[`D:\23\deployment\README.md`](../../deployment/README.md)**.

Цей каталог містить лише копії-форвардери. `publish_update.py` тут нічого не робить сам,
а викликає авторитетний `D:\23\deployment\publish_update.py` (який запускає
`D:\23\fabric-server\build.ps1` і всі дзеркала). Щоб випустити оновлення — запускай саме
кореневий скрипт:

```powershell
cd D:\23
python deployment\publish_update.py --title "…" --summary "…" --note "…"
```
