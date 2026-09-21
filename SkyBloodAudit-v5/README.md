# SkyBloodAudit v5

V5 construída a partir dos JARs reais enviados.

## Integrações diretas confirmadas
- EconomyShopGUI 7.2.1: `PostTransactionEvent`
- ExcellentCrates 6.6.1: `CrateObtainRewardEvent`
- RoseStacker 1.5.41: `SpawnerStackEvent` e `PreDropStackedItemsEvent`
- AxMinions 1.0.20: eventos de pickup/farmer/fisher
- SuperiorSkyblock2 2026.2: depósito e saque do banco da ilha

## AmazingAuction
O JAR fornecido não expõe eventos Bukkit públicos próprios de compra/venda. Por isso a V5 mantém para ele a correlação por GUI, comandos e mudanças Vault. Isso é intencional para não acoplar o plugin a classes internas frágeis.

## Fail-safe
Os adapters diretos são carregados por reflexão. O SkyBloodAudit não possui dependência de compilação rígida nesses seis plugins.
Se uma atualização remover/renomear um evento:
1. o adapter específico falha;
2. um alerta é gerado;
3. o Core, SQLite, Discord, comandos, economia e AntiDupe continuam;
4. o fallback genérico continua ativo.

`integration-versions.yml` guarda as versões vistas e alerta quando alguma muda.

## SQLite
Continua em `plugins/SkyBloodAudit/audit.db`.
Agora eventos confirmados por integrações diretas também entram em `audit_events` com `type=INTEGRATION`.

Exemplo:
```sql
SELECT ts,player_name,action,detail
FROM audit_events
WHERE type='INTEGRATION'
ORDER BY ts DESC;
```

## Staff
`/lp group dono permission set skybloodaudit.staff true`

## Build
Java 21:
`mvn clean package`

Saída:
`target/SkyBloodAudit-5.0.0.jar`

O sqlite-jdbc é sombreado para dentro do JAR final.
